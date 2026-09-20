package net.gabriele333.gabrielequests.client.structure;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.gabriele333.gabrielequests.client.render.SceneCache;
import net.gabriele333.gabrielequests.client.render.SceneRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The display structure's binding to the shared off-screen renderer: it decodes the
 * template through {@link StructureLoader} and supplies the hook that rebuilds the stored
 * block entities. Caching, framing and all the GL-state handling live in
 * {@link SceneCache} / {@link SceneRenderer}, shared with the display multiblock.
 */
public final class StructureRenderer {

    /**
     * Generous on purpose: one chapter page routinely holds a dozen displays, and a cache
     * smaller than what is on screen is worse than useless (see {@link SceneCache}, which
     * also caps the total by memory).
     */
    private static final int MAX_CACHED = 48;

    private static final SceneCache<StructureSpec> CACHE =
            new SceneCache<>(new SceneCache.Scene<>() {
                @Override
                @Nullable
                public Map<BlockPos, BlockState> blocks(StructureSpec spec) {
                    StructureData data = data(spec);
                    return data == null ? null : data.blocks();
                }

                @Override
                public BlockPos size(StructureSpec spec) {
                    StructureData data = data(spec);
                    return data == null ? BlockPos.ZERO : data.size();
                }

                @Override
                public SceneRenderer.Hooks hooks(StructureSpec spec) {
                    StructureData data = data(spec);
                    return data == null ? SceneRenderer.Hooks.NONE
                            : new StructureHooks(data.blockEntities());
                }

                @Override
                public String describe(StructureSpec spec) {
                    return spec.serialize();
                }
            }, MAX_CACHED);

    private StructureRenderer() {
    }

    /**
     * The blocks behind a spec: a whole structure goes through the composite/jigsaw
     * resolver, a single piece straight to the template loader. Both are cached at their
     * own level, so this stays cheap enough to call once per {@link SceneCache} query.
     */
    @Nullable
    private static StructureData data(StructureSpec spec) {
        return spec.whole()
                ? WholeStructures.build(spec.structureId(), spec.markers())
                : StructureLoader.load(spec.structureId(), spec.markers());
    }

    /**
     * Returns the cached texture for the spec at the given view angles, rendering it now
     * (once) if needed. {@code null} means the structure cannot be rendered (no mod provides
     * it, or the template is unreadable) - callers draw a placeholder instead.
     */
    @Nullable
    public static RenderTarget get(StructureSpec spec, int yaw, int pitch, GuiGraphics graphics) {
        return CACHE.get(spec, yaw, pitch, graphics);
    }

    /** Live preview with explicit view angles, for the orbit drag. */
    @Nullable
    public static RenderTarget preview(StructureSpec spec, int yaw, int pitch, GuiGraphics graphics) {
        return CACHE.preview(spec, yaw, pitch, false, graphics);
    }

    /**
     * Rebuilds each block entity from the tag the template stored for it, so chests show
     * their model, signs their text and banners their patterns, instead of the bare default
     * a fresh block entity would render. Anything the tag cannot produce (a block entity
     * type that no longer matches, a mod that changed its format) falls back to the default
     * empty block entity, and the shared renderer's per-block try/catch covers the rest.
     */
    private record StructureHooks(Map<BlockPos, CompoundTag> blockEntities) implements SceneRenderer.Hooks {

        @Override
        @Nullable
        public BlockEntity createBlockEntity(EntityBlock block, BlockPos pos, BlockState state,
                                             Map<BlockPos, BlockState> blocks) {
            CompoundTag tag = blockEntities.get(pos);
            // The "id" check keeps us off loadStatic's own error path: without it every
            // block-entity tag a mod wrote without a type id would log a vanilla error,
            // once per block, on every re-render.
            if (tag != null && tag.contains("id", Tag.TAG_STRING) && Minecraft.getInstance().level != null) {
                BlockEntity loaded = BlockEntity.loadStatic(pos, state, tag,
                        Minecraft.getInstance().level.registryAccess());
                if (loaded != null) {
                    return loaded;
                }
            }
            return block.newBlockEntity(pos, state);
        }
    }
}
