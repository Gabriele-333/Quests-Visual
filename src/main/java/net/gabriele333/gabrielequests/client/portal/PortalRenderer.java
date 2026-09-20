package net.gabriele333.gabrielequests.client.portal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.gabriele333.gabrielequests.client.render.SceneCache;
import net.gabriele333.gabrielequests.client.render.SceneRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The display portal's binding to the shared off-screen renderer: the blocks come from
 * {@link PortalShape}, everything else (caching, framing, the GL-state handling, the sandboxed
 * block-entity phase) is the same machinery the display multiblock and the display structure
 * use.
 *
 * <p>The block-entity phase is what actually paints the vanilla end portal and end gateway:
 * both blocks render nothing as a model ({@code RenderShape.INVISIBLE}) and get their starfield
 * from a block-entity renderer, which the shared pass runs against a fake block entity.</p>
 */
public final class PortalRenderer {

    /** As for the other displays: a page full of them must all stay resident. */
    private static final int MAX_CACHED = 48;

    private static final SceneCache<PortalSpec> CACHE =
            new SceneCache<>(new SceneCache.Scene<>() {
                @Override
                @Nullable
                public Map<BlockPos, BlockState> blocks(PortalSpec spec) {
                    return spec.shape().generate(spec);
                }

                @Override
                public BlockPos size(PortalSpec spec) {
                    return spec.shape().size(spec);
                }

                @Override
                public SceneRenderer.Hooks hooks(PortalSpec spec) {
                    return PortalHooks.INSTANCE;
                }

                @Override
                public String describe(PortalSpec spec) {
                    return spec.serialize();
                }
            }, MAX_CACHED);

    private PortalRenderer() {
    }

    /**
     * Returns the cached texture for the spec at the given view angles, rendering it now (once)
     * if needed. {@code null} means the portal cannot be rendered (its mod is not installed, or
     * the render failed) - callers draw a placeholder instead.
     */
    @Nullable
    public static RenderTarget get(PortalSpec spec, int yaw, int pitch, GuiGraphics graphics) {
        return CACHE.get(spec, yaw, pitch, graphics);
    }

    /** Live preview with explicit view angles, for the orbit drag. */
    @Nullable
    public static RenderTarget preview(PortalSpec spec, int yaw, int pitch, GuiGraphics graphics) {
        return CACHE.preview(spec, yaw, pitch, false, graphics);
    }

    /**
     * Ages the fake end-gateway block entity past its spawn animation. A fresh one starts at
     * age 0, which {@code TheEndGatewayBlockEntity#isSpawning} reads as "just placed" and makes
     * its renderer draw the tall purple beam - in a still image framed on a 3&#215;5&#215;3
     * cage that is a stripe through the picture, not a gateway. Age 400 is past the 200-tick
     * animation, so only the portal surface is drawn. Nothing else needs a hook: the end portal
     * and every mod portal render correctly from a default block entity.
     */
    private enum PortalHooks implements SceneRenderer.Hooks {
        INSTANCE;

        @Override
        @Nullable
        public BlockEntity createBlockEntity(EntityBlock block, BlockPos pos, BlockState state,
                                             Map<BlockPos, BlockState> blocks) {
            if (state.is(Blocks.END_GATEWAY) && Minecraft.getInstance().level != null) {
                CompoundTag tag = new CompoundTag();
                tag.putString("id", "minecraft:end_gateway");
                tag.putLong("Age", 400L);
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
