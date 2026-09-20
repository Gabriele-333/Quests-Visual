package net.gabriele333.gabrielequests.client.multiblock;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.gabriele333.gabrielequests.client.render.SceneCache;
import net.gabriele333.gabrielequests.client.render.SceneRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The display multiblock's binding to the shared off-screen renderer: it generates the
 * blocks from a {@link MultiblockSpec} and supplies the mod-specific
 * {@link SceneRenderer.Hooks} that make Mekanism / Draconic Evolution / Ender IO
 * multiblocks look formed and powered. Caching, framing and all the GL-state handling live
 * in {@link SceneCache} / {@link SceneRenderer}, shared with the display structure.
 */
public final class MultiblockRenderer {

    /** As for structures: a page full of display multiblocks must all stay resident. */
    private static final int MAX_CACHED = 48;

    private static final SceneCache<MultiblockSpec> CACHE =
            new SceneCache<>(new SceneCache.Scene<>() {
                @Override
                @Nullable
                public Map<BlockPos, BlockState> blocks(MultiblockSpec spec) {
                    return spec.type().generate(spec);
                }

                @Override
                public BlockPos size(MultiblockSpec spec) {
                    return new BlockPos(spec.width(), spec.height(), spec.depth());
                }

                @Override
                public SceneRenderer.Hooks hooks(MultiblockSpec spec) {
                    return new MultiblockHooks(spec);
                }

                @Override
                public String describe(MultiblockSpec spec) {
                    return spec.serialize();
                }
            }, MAX_CACHED);

    private MultiblockRenderer() {
    }

    /**
     * Returns the cached texture for the spec, rendering it now (once) if needed.
     * {@code null} means the structure cannot be rendered (mod blocks missing or a render
     * failure) - callers draw a placeholder instead.
     */
    @Nullable
    public static RenderTarget get(MultiblockSpec spec, int yaw, int pitch, GuiGraphics graphics) {
        return CACHE.get(spec, yaw, pitch, graphics);
    }

    /** Live preview with explicit view angles, for the orbit drag. */
    @Nullable
    public static RenderTarget preview(MultiblockSpec spec, int yaw, int pitch, GuiGraphics graphics) {
        // Animated types skip the change check: their BER visuals are time-driven, so
        // the whole point is re-rendering the same key every frame.
        return CACHE.preview(spec, yaw, pitch, spec.type().animated(), graphics);
    }

    /**
     * Feeds the sandboxed BER phase the state a real multiblock would have: fuel and
     * facing for the Draconic reactor/energy core, rotor blades for the Mekanism turbine
     * (they are items in the block entity, not blocks), plus our own energy-bar overlay
     * for the Ender IO capacitor bank, whose own bar renderer cannot cope with a fake
     * block entity (see {@link EnergyBarRenderer}).
     */
    private record MultiblockHooks(MultiblockSpec spec) implements SceneRenderer.Hooks {

        @Override
        @Nullable
        public BlockEntity createBlockEntity(EntityBlock block, BlockPos pos, BlockState state,
                                             Map<BlockPos, BlockState> blocks) {
            BlockEntity be = block.newBlockEntity(pos, state);
            if (be == null) {
                return null;
            }
            if (ModList.get().isLoaded("draconicevolution")) {
                DraconicBeSetup.configure(be, pos, blocks, spec);
            }
            if (ModList.get().isLoaded("mekanismgenerators")) {
                MekanismBeSetup.configure(be, pos, blocks);
            }
            return be;
        }

        @Override
        public void afterBlockEntity(BlockEntityRenderer<BlockEntity> renderer, BlockEntity be,
                                     BlockPos pos, Map<BlockPos, BlockState> blocks,
                                     float partialTick, PoseStack pose,
                                     MultiBufferSource.BufferSource buffers) {
            if (ModList.get().isLoaded("brandonscore")) {
                BrandonsCoreBerHelper.renderTransparent(renderer, be, partialTick, pose, buffers);
            }
            if (ModList.get().isLoaded("draconicevolution")) {
                DraconicBeSetup.renderExtras(renderer, be, pos, blocks, pose, buffers);
            }
        }

        @Override
        public void overlay(PoseStack pose, MultiBufferSource.BufferSource buffers, int w, int h, int d) {
            if (spec.type().id().equals("capacitor_bank")) {
                EnergyBarRenderer.draw(pose, buffers, w, h, d, chargeFraction());
            }
        }

        /** The 0..1 energy-bar fill from the spec's "charge" option (percent), defaulting full. */
        private float chargeFraction() {
            try {
                return Math.clamp(Integer.parseInt(spec.option("charge")), 0, 100) / 100F;
            } catch (NumberFormatException e) {
                return 1F;
            }
        }
    }
}
