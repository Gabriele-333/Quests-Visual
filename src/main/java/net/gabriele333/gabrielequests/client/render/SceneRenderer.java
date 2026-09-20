package net.gabriele333.gabrielequests.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared off-screen 3D pass: takes a map of block positions to states and paints it
 * into a framebuffer, seen from a turntable view (yaw/pitch), framed to fill the image.
 * Used by the display multiblock (a structure generated from a
 * {@link net.gabriele333.gabrielequests.client.multiblock.MultiblockSpec}) and by the
 * display structure (a structure read from a {@code .nbt} template) - the two differ only
 * in where the blocks come from and in their {@link Hooks}.
 *
 * <p>Rendering off-screen sidesteps the GUI depth-range/state problems that plagued the
 * item-icon feature (see the white-outline fix in {@code ChapterImageButtonMixin}): the 3D
 * pass happens against a private depth buffer with its own projection matrix, and every
 * piece of global state it touches (projection, model-view, fog, shader color, scissor,
 * bound framebuffer) is restored before returning to the GUI.</p>
 *
 * <p>Nothing here throws on bad content: callers wrap the whole call, and the two places
 * that run third-party code per block (block-entity renderers, mod model-data hooks) are
 * individually guarded so one hostile block costs its own visuals and nothing else.</p>
 */
public final class SceneRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Session cache of block types whose BER failed once - never retried. */
    private static final Set<Block> BER_SKIP = new HashSet<>();

    private SceneRenderer() {
    }

    /**
     * Per-scene customisation. The defaults are the plain-vanilla behaviour, which is
     * exactly what the display structure wants for most blocks; the multiblock overrides
     * the block-entity hooks to feed Mekanism/Draconic renderers the state their fake block
     * entities lack, and the structure overrides {@link #createBlockEntity} to restore the
     * block-entity NBT stored in the template (chest contents, sign text, banner patterns).
     */
    public interface Hooks {

        Hooks NONE = new Hooks() {
        };

        /**
         * Builds the fake block entity used for the BER phase, or {@code null} to skip this
         * block. Called inside the per-block try/catch; the level is attached by the caller.
         */
        @Nullable
        default BlockEntity createBlockEntity(EntityBlock block, BlockPos pos, BlockState state,
                                              Map<BlockPos, BlockState> blocks) {
            return block.newBlockEntity(pos, state);
        }

        /** Extra draw calls after a block entity's own renderer ran (pose is at the block). */
        default void afterBlockEntity(BlockEntityRenderer<BlockEntity> renderer, BlockEntity be,
                                      BlockPos pos, Map<BlockPos, BlockState> blocks,
                                      float partialTick, PoseStack pose,
                                      MultiBufferSource.BufferSource buffers) {
        }

        /** Scene-wide overlay drawn last, in structure space (0,0,0)-(w,h,d). */
        default void overlay(PoseStack pose, MultiBufferSource.BufferSource buffers,
                             int w, int h, int d) {
        }
    }

    /**
     * One full off-screen pass into the given target, restoring all global state. The
     * structure is centred on the origin and framed so its rotated bounding box fills the
     * square image.
     *
     * @param w,h,d the structure's bounding-box size in blocks; block positions are
     *              expected to run 0..w-1 / 0..h-1 / 0..d-1
     */
    public static void render(RenderTarget target, Map<BlockPos, BlockState> blocks,
                              int w, int h, int d, float yaw, float pitch,
                              GuiGraphics graphics, Hooks hooks) {
        Minecraft mc = Minecraft.getInstance();
        // Push out any batched GUI geometry before switching framebuffers, otherwise it
        // would be flushed into ours.
        graphics.flush();

        target.setClearColor(0F, 0F, 0F, 0F);
        target.setFilterMode(GL11.GL_LINEAR); // smooth when the image is scaled in the GUI

        // ---- save global state we are about to touch ----
        boolean hadScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        float[] shaderColor = RenderSystem.getShaderColor().clone();
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();

        try {
            if (hadScissor) {
                // Panel clipping must not clip the FBO - glClear honours the scissor test
                // too, so this has to happen before the clear below. Raw GL on purpose:
                // FTB Library enables the scissor with a bare GL11.glEnable
                // (GuiHelper#pushScissor), so GlStateManager's cached BooleanState still
                // reads "disabled" and GlStateManager._disableScissorTest() would skip the
                // GL call entirely - leaving the panel's scissor box (window coordinates!)
                // clipping our framebuffer, which cut a strip off the left of every
                // structure that reached that far.
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);

            // Tight framing: run the 8 corners of the (centred) structure box through the
            // same rotation used below, then centre a square orthographic window on the
            // projected bounds plus a padding margin - the structure can never poke out
            // of the image, and elongated builds fill the frame instead of floating
            // inside a bounding-sphere-sized one.
            Matrix4f rotation = new Matrix4f()
                    .rotateX((float) Math.toRadians(pitch))
                    .rotateY((float) Math.toRadians(yaw));
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            Vector3f corner = new Vector3f();
            for (int i = 0; i < 8; i++) {
                corner.set(
                        ((i & 1) == 0 ? -w : w) / 2F,
                        ((i & 2) == 0 ? -h : h) / 2F,
                        ((i & 4) == 0 ? -d : d) / 2F);
                rotation.transformPosition(corner);
                minX = Math.min(minX, corner.x); maxX = Math.max(maxX, corner.x);
                minY = Math.min(minY, corner.y); maxY = Math.max(maxY, corner.y);
                minZ = Math.min(minZ, corner.z); maxZ = Math.max(maxZ, corner.z);
            }
            float halfSpan = Math.max(maxX - minX, maxY - minY) / 2F * 1.06F + 0.05F;
            float centerX = (minX + maxX) / 2F;
            float centerY = (minY + maxY) / 2F;
            float zRange = Math.max(Math.abs(minZ), Math.abs(maxZ)) + 1F;
            Matrix4f projection = new Matrix4f().setOrtho(
                    centerX - halfSpan, centerX + halfSpan,
                    centerY - halfSpan, centerY + halfSpan,
                    -zRange, zRange);
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.enableDepthTest();

            // Turntable view: pitch down, then yaw, structure centred on the origin.
            PoseStack pose = new PoseStack();
            pose.mulPose(Axis.XP.rotationDegrees(pitch));
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.translate(-w / 2F, -h / 2F, -d / 2F);

            SceneLevel level = new SceneLevel(blocks, h);
            BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            RandomSource random = RandomSource.create();
            Map<BlockPos, ModelData> extraModelData = computeExtraModelData(blocks);

            // Blocks buried inside the structure cannot contribute a single visible face, and
            // a big assembled structure is mostly buried blocks. Face culling already threw
            // their geometry away, but we were still paying the model lookup and the
            // tesselateBlock call for each - which is the bulk of the cost on a 40k-block
            // dungeon. Dropping them up front is invisible and makes the one-off render (and
            // every orbit re-render) dramatically cheaper.
            Map<BlockPos, BlockState> visible = cullEnclosed(blocks, level);

            drawByRenderType(visible, level, dispatcher, buffers, random, extraModelData, pose);

            // Phase 3: block-entity renderers, for blocks whose visuals live in a BER
            // instead of a baked model (chests and signs in a structure, or the whole
            // Draconic Evolution reactor in a multiblock). Buried ones are skipped for the
            // same reason as above - a chest walled in on all six sides draws nothing.
            renderBlockEntities(visible, pose, buffers, mc, hooks);

            // Phase 4: the scene's own overlays (currently only the Ender IO capacitor
            // bank energy bar - see EnergyBarRenderer).
            hooks.overlay(pose, buffers, w, h, d);
        } finally {
            // ---- restore everything, even if tesselation blew up ----
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            mc.getMainRenderTarget().bindWrite(true);
            if (hadScissor) {
                // Raw again (see above), so GL and GlStateManager's cache end up exactly
                // as they were before this pass.
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    /** One block queued for tesselation, with the model lookups already done. */
    private record Draw(BlockPos pos, BlockState state, BakedModel model, ModelData data) {
    }

    /**
     * Tesselates the scene <b>one render type at a time</b>, opaque types first and
     * translucent last.
     *
     * <p>The grouping is not tidiness, it is the whole performance story of a large scene.
     * {@code BufferSource} keeps a single shared byte buffer for every render type that is not
     * one of its handful of fixed ones - and the chunk types (solid, cutout, cutout_mipped,
     * translucent) are all sharing it. Asking it for a different type than the one currently
     * open <b>ends the open batch and draws it</b>. Walking blocks in map order therefore
     * issues a draw call every time the material changes from stone to glass to torch and back,
     * which on Twilight Forest's baked lich tower meant thousands of GL uploads and 9.3 seconds
     * for 44k blocks - while a hollow hill of the same size, being all one type, took 164 ms.
     * Grouping first turns that into about four draw calls regardless of scene size.</p>
     *
     * <p>It also halves the model work: the old two-pass loop looked up the baked model and
     * the mod model data once per pass for every block, and now does it once.</p>
     */
    private static void drawByRenderType(Map<BlockPos, BlockState> visible, SceneLevel level,
                                         BlockRenderDispatcher dispatcher,
                                         MultiBufferSource.BufferSource buffers, RandomSource random,
                                         Map<BlockPos, ModelData> extraModelData, PoseStack pose) {
        Map<RenderType, List<Draw>> byType = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : visible.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            BakedModel model = dispatcher.getBlockModel(state);
            ModelData data = model.getModelData(level, pos, state,
                    extraModelData.getOrDefault(pos, ModelData.EMPTY));
            Draw draw = new Draw(pos, state, model, data);
            for (RenderType renderType : model.getRenderTypes(state, random, data)) {
                byType.computeIfAbsent(renderType, type -> new ArrayList<>()).add(draw);
            }
        }
        // Opaque before translucent, so the blending sees the finished opaque depth underneath.
        List<RenderType> order = new ArrayList<>(byType.keySet());
        order.sort(Comparator.comparingInt(type -> type == RenderType.translucent() ? 1 : 0));

        for (RenderType renderType : order) {
            VertexConsumer buffer = buffers.getBuffer(renderType);
            for (Draw draw : byType.get(renderType)) {
                pose.pushPose();
                pose.translate(draw.pos().getX(), draw.pos().getY(), draw.pos().getZ());
                dispatcher.getModelRenderer().tesselateBlock(level, draw.model(), draw.state(),
                        draw.pos(), pose, buffer, true, random, draw.state().getSeed(draw.pos()),
                        OverlayTexture.NO_OVERLAY, draw.data(), renderType);
                pose.popPose();
            }
            buffers.endBatch();
        }
    }

    /**
     * Drops every block whose six neighbours all render as full opaque cubes: nothing of it
     * can ever be seen, so tesselating it is pure cost. Blocks on the structure's surface,
     * next to air, glass, stairs, slabs or anything else that does not fill its cube are all
     * kept, which is why this is safe rather than an approximation.
     *
     * <p>Returns the original map when nothing was culled, so small scenes pay nothing.</p>
     */
    private static Map<BlockPos, BlockState> cullEnclosed(Map<BlockPos, BlockState> blocks,
                                                          SceneLevel level) {
        // Memoised by identity: the block map of a given scene is immutable and reused, so
        // an orbit drag re-renders the same map dozens of times and would otherwise walk all
        // 25k+ blocks (and allocate a fresh map) for every one of them.
        Map<BlockPos, BlockState> memoised = CULLED.get(blocks);
        if (memoised != null) {
            return memoised;
        }
        Map<BlockPos, BlockState> visible = new HashMap<>(blocks.size());
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            boolean enclosed = true;
            for (Direction direction : DIRECTIONS) {
                neighbour.setWithOffset(pos, direction);
                BlockState side = blocks.get(neighbour);
                if (side == null || !side.isSolidRender(level, neighbour)) {
                    enclosed = false;
                    break;
                }
            }
            if (!enclosed) {
                visible.put(pos, entry.getValue());
            }
        }
        Map<BlockPos, BlockState> result = visible.size() == blocks.size() ? blocks : visible;
        if (result != blocks) {
            LOGGER.info("[GabrieleQuests/QuestsTools] Culled {} buried blocks of {} before tesselating",
                    blocks.size() - result.size(), blocks.size());
        }
        if (CULLED.size() >= MAX_CULLED) {
            CULLED.clear(); // tiny and identity-keyed; a wholesale reset is cheap enough
        }
        CULLED.put(blocks, result);
        return result;
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Identity-keyed memo of {@link #cullEnclosed}; see the note there. */
    private static final int MAX_CULLED = 16;
    private static final Map<Map<BlockPos, BlockState>, Map<BlockPos, BlockState>> CULLED =
            new IdentityHashMap<>();

    /**
     * Renders block entities with their real {@link BlockEntityRenderer}s, sandboxed:
     * each block gets a fresh fake block entity attached to the actual client level (for
     * time/random access), built and configured by the scene's {@link Hooks}, and runs
     * inside its own try/catch - a renderer that cannot cope with a fake block entity costs
     * that block type its visuals (cached in {@link #BER_SKIP}, logged once), never the
     * render. Animated renderers are simply frozen at one frame, which is fine for a
     * one-shot cached texture.
     */
    private static void renderBlockEntities(Map<BlockPos, BlockState> blocks, PoseStack pose,
                                            MultiBufferSource.BufferSource buffers, Minecraft mc,
                                            Hooks hooks) {
        if (mc.level == null) {
            return;
        }
        boolean rendered = false;
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        Lighting.setupFor3DItems(); // deterministic entity-shader light, same as GUI items
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            if (!(state.getBlock() instanceof EntityBlock entityBlock) || BER_SKIP.contains(state.getBlock())) {
                continue;
            }
            BlockPos pos = entry.getKey();
            try {
                BlockEntity be = hooks.createBlockEntity(entityBlock, pos, state, blocks);
                if (be == null) {
                    continue;
                }
                be.setLevel(mc.level);
                BlockEntityRenderer<BlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
                if (renderer == null) {
                    continue;
                }
                pose.pushPose();
                try {
                    pose.translate(pos.getX(), pos.getY(), pos.getZ());
                    renderer.render(be, partialTick, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    hooks.afterBlockEntity(renderer, be, pos, blocks, partialTick, pose, buffers);
                } finally {
                    pose.popPose();
                }
                rendered = true;
            } catch (Throwable t) {
                BER_SKIP.add(state.getBlock());
                LOGGER.warn("[GabrieleQuests/QuestsTools] Block entity renderer failed for {} - skipping this block type",
                        state.getBlock(), t);
            }
        }
        if (rendered) {
            buffers.endBatch();
        }
    }

    /**
     * Per-position ModelData that formed multiblock models normally source from their
     * block entities (which our fake level does not have). Currently AE2 only. Any
     * failure here (e.g. AE2 internals changed) degrades to the unconnected formed look
     * instead of breaking the render.
     */
    private static Map<BlockPos, ModelData> computeExtraModelData(Map<BlockPos, BlockState> blocks) {
        Map<BlockPos, ModelData> map = new HashMap<>();
        try {
            if (ModList.get().isLoaded("ae2")) {
                Ae2ModelData.augment(blocks, map);
            }
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] AE2 model-data hook failed; formed models render unconnected", t);
        }
        return map;
    }
}
