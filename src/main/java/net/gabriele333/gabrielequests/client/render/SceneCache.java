package net.gabriele333.gabrielequests.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Renders a scene <b>once</b> into an off-screen framebuffer and caches the resulting
 * texture; drawing the display afterwards costs a single textured quad per frame, no
 * matter how big the structure is. This is the "no lag" half of the design - the "no
 * crash" half is that everything here is wrapped so a failure is logged once, cached as a
 * permanent fallback for that key, and never retried every frame.
 *
 * <p>{@link #preview} is the interactive variant used while orbit-dragging a display: it
 * re-renders into one shared, reused target, and only when the requested angles/key
 * actually changed since the previous frame (angles are whole degrees, so a slow drag
 * re-tesselates at most once per degree). The cached path and the LRU stay untouched during
 * a drag; on release the final angles land in the spec and the normal cached path takes
 * over - the framing formula is identical, so there is no visual snap.</p>
 *
 * @param <K> the spec type that identifies (and fully describes) a scene
 */
public final class SceneCache<K> {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /**
     * Off-screen resolution. A multiblock is at most 18 blocks across, so 512 gives it ~28
     * pixels per block; a whole assembled structure can be 100+ across, where 512 would put
     * fewer than 5 pixels on a block and the result reads as mush. Big scenes therefore get
     * a bigger target - but only one step up, because every target costs
     * {@code size * size * 4} bytes twice (colour plus depth) and the cache holds several.
     */
    private static final int TEXTURE_SIZE = 512;
    private static final int LARGE_TEXTURE_SIZE = 1024;
    private static final int LARGE_SCENE_BLOCKS = 24;

    /**
     * Above this many blocks, re-tesselating the scene costs enough that doing it for every
     * degree of an orbit drag stutters. Heavy scenes snap their preview angles to
     * {@link #HEAVY_ANGLE_STEP} instead, so a drag re-renders a handful of times rather than
     * a hundred. The cached (released) render still uses the exact angle.
     */
    private static final int HEAVY_BLOCKS = 6_000;
    private static final int HEAVY_ANGLE_STEP = 15;

    /** Turns a spec into the blocks to draw and the way to draw them. */
    public interface Scene<K> {

        /** The blocks of this scene, or {@code null}/empty when it cannot be rendered. */
        @Nullable
        Map<BlockPos, BlockState> blocks(K key);

        /** Bounding-box size in blocks; block positions run 0..size-1 on each axis. */
        BlockPos size(K key);

        default SceneRenderer.Hooks hooks(K key) {
            return SceneRenderer.Hooks.NONE;
        }

        /** Short identification for the log lines. */
        String describe(K key);
    }

    /**
     * How much GPU memory the cached targets may take together. The entry count alone is a
     * bad limit: a chapter page can easily hold a dozen displays, and if the cache is one
     * slot short of the number actually on screen then <em>every</em> lookup misses and
     * evicts the entry needed a moment later - a hundred percent miss rate that re-renders
     * every structure on every frame. Sizing by bytes lets a page of small displays all stay
     * resident while still capping what a page of huge ones can hold.
     *
     * <p>A target costs {@code resolution² * 4} bytes twice (colour plus depth): 2 MB at
     * 512, 8 MB at 1024.</p>
     */
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    private final Scene<K> scene;
    private final int maxCached;
    private long cachedBytes;
    /**
     * LRU cache, (scene + view angles) -> render target; a null target means "failed, don't
     * retry". The <b>angles are part of the key</b>: an icon whose view angle is merely being
     * held (the author spun it once and let go) must land here rather than on the scratch
     * target below, otherwise two such icons on the same page take turns invalidating the one
     * scratch target and each re-renders every single frame.
     */
    private final LinkedHashMap<String, RenderTarget> cache = new LinkedHashMap<>(16, 0.75F, true);

    /** Scenes known to be expensive to tesselate; see {@link #HEAVY_ANGLE_STEP}. */
    private final Set<String> heavy = new HashSet<>();

    @Nullable
    private RenderTarget previewTarget;
    private int previewTargetSize;
    @Nullable
    private String previewKey;
    private boolean previewOk;

    public SceneCache(Scene<K> scene, int maxCached) {
        this.scene = scene;
        this.maxCached = maxCached;
    }

    /**
     * Returns the cached texture for the key, rendering it now (once) if needed.
     * {@code null} means the scene cannot be rendered (blocks missing or a render failure)
     * - callers draw a placeholder instead.
     */
    @Nullable
    public RenderTarget get(K key, int yaw, int pitch, GuiGraphics graphics) {
        String cacheKey = scene.describe(key) + "|" + yaw + "|" + pitch;
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        RenderTarget target = null;
        try {
            Map<BlockPos, BlockState> blocks = scene.blocks(key);
            if (blocks != null && !blocks.isEmpty()) {
                BlockPos size = scene.size(key);
                int resolution = textureSizeFor(size);
                target = new TextureTarget(resolution, resolution, true, Minecraft.ON_OSX);
                long started = System.currentTimeMillis();
                try {
                    SceneRenderer.render(target, blocks, size.getX(), size.getY(), size.getZ(),
                            yaw, pitch, graphics, scene.hooks(key));
                    LOGGER.info("[GabrieleQuests/QuestsTools] Rendered {} ({} blocks, {}px, {} ms)",
                            scene.describe(key), blocks.size(), resolution,
                            System.currentTimeMillis() - started);
                } catch (Throwable t) {
                    target.destroyBuffers();
                    throw t;
                }
                if (blocks.size() > HEAVY_BLOCKS) {
                    heavy.add(scene.describe(key));
                }
            }
        } catch (Throwable t) {
            target = null;
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to render {} - using placeholder",
                    scene.describe(key), t);
        }
        cache.put(cacheKey, target);
        cachedBytes += bytesOf(target);
        // Evict least-recently-used entries until both limits are satisfied, but never drop
        // the last one - a single display bigger than the whole budget must still be cached,
        // or it would re-render on every frame.
        while (cache.size() > 1 && (cache.size() > maxCached || cachedBytes > MAX_BYTES)) {
            var eldest = cache.entrySet().iterator().next();
            if (eldest.getValue() != null) {
                cachedBytes -= bytesOf(eldest.getValue());
                eldest.getValue().destroyBuffers();
            }
            cache.remove(eldest.getKey());
        }
        return target;
    }

    /**
     * Live preview with explicit view angles, for the orbit drag. Re-renders only when
     * (key, yaw, pitch) differ from the previous call - unless {@code alwaysRedraw}, which
     * animated scenes use because their visuals are time-driven. The shared target is
     * created lazily and kept for the session.
     */
    @Nullable
    public RenderTarget preview(K key, int yaw, int pitch, boolean alwaysRedraw, GuiGraphics graphics) {
        String description = scene.describe(key);
        String cacheKey = null;
        try {
            // Heavy scenes drag in coarse steps (see HEAVY_ANGLE_STEP); light ones follow the
            // mouse degree by degree. Whether this scene is heavy is remembered from the
            // previous render, so that deciding it costs nothing here.
            if (heavy.contains(description)) {
                yaw = Math.round(yaw / (float) HEAVY_ANGLE_STEP) * HEAVY_ANGLE_STEP;
                pitch = Math.round(pitch / (float) HEAVY_ANGLE_STEP) * HEAVY_ANGLE_STEP;
            }
            cacheKey = description + "|" + yaw + "|" + pitch;
            // This check has to come BEFORE asking the scene for its blocks: an icon whose
            // view angle is being held calls in every single frame, and for an assembled
            // structure "give me your blocks" is a whole jigsaw solve.
            if (!alwaysRedraw && cacheKey.equals(previewKey)) {
                return previewOk ? previewTarget : null;
            }
            previewKey = cacheKey;
            previewOk = false;

            Map<BlockPos, BlockState> blocks = scene.blocks(key);
            if (blocks == null || blocks.isEmpty()) {
                return null;
            }
            if (blocks.size() > HEAVY_BLOCKS) {
                heavy.add(description);
            }
            BlockPos size = scene.size(key);
            // The drag preview always renders at the base resolution, even for a scene the
            // cached path would give a bigger target: while the model is spinning under the
            // mouse a quarter of the pixels is not noticeable, and it is a quarter of the
            // fill cost per step. Letting go re-renders through the cache at full size.
            int resolution = TEXTURE_SIZE;
            if (previewTarget != null && previewTargetSize != resolution) {
                // A previous drag left a target of the wrong resolution for this scene.
                previewTarget.destroyBuffers();
                previewTarget = null;
            }
            if (previewTarget == null) {
                previewTarget = new TextureTarget(resolution, resolution, true, Minecraft.ON_OSX);
                previewTargetSize = resolution;
            }
            SceneRenderer.render(previewTarget, blocks, size.getX(), size.getY(), size.getZ(),
                    yaw, pitch, graphics, scene.hooks(key));
            previewOk = true;
            return previewTarget;
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to render rotation preview {}", cacheKey, t);
            return null;
        }
    }

    /** Colour plus depth, four bytes per pixel each. */
    private static long bytesOf(@Nullable RenderTarget target) {
        if (target == null) {
            return 0L;
        }
        return (long) target.width * target.height * 4L * 2L;
    }

    /** Bigger scenes get a bigger off-screen target so a block keeps enough pixels. */
    private static int textureSizeFor(BlockPos size) {
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        return longest > LARGE_SCENE_BLOCKS ? LARGE_TEXTURE_SIZE : TEXTURE_SIZE;
    }
}
