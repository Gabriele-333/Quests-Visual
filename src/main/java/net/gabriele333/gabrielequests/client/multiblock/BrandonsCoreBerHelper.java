package net.gabriele333.gabrielequests.client.multiblock;

import com.brandon3055.brandonscore.client.render.BlockEntityRendererTransparent;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * BrandonsCore-only hook, referenced exclusively behind a
 * {@code ModList.isLoaded("brandonscore")} guard in {@code MultiblockRenderer}: BC
 * renderers split translucent effects (e.g. the draconic reactor's energy shield) into a
 * separate {@code renderTransparent} phase that the vanilla dispatcher never calls, so
 * the sandboxed BER phase runs it explicitly after the normal render.
 */
final class BrandonsCoreBerHelper {

    private BrandonsCoreBerHelper() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void renderTransparent(BlockEntityRenderer<?> renderer, BlockEntity be, float partialTick,
                                  PoseStack pose, MultiBufferSource buffers) {
        if (renderer instanceof BlockEntityRendererTransparent transparent) {
            transparent.renderTransparent(be, partialTick, pose, buffers,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }
}
