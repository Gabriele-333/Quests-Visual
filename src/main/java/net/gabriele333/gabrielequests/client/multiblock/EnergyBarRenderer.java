package net.gabriele333.gabrielequests.client.multiblock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the Ender IO capacitor bank's energy bar - one bar, running the full height of the
 * middle column of one face - with Ender IO's own textures and geometry, straight into the
 * off-screen pass.
 *
 * <p>This is a transcription of {@code CapacitorBankBER}, not an approximation: same
 * sprites (the 4px trough, capped at both ends of a run, and the 2px violet fill), same
 * pixel arithmetic (a run of {@code n} blocks holds {@code 10 + (n-1)*16} fill pixels, the
 * bottom block taking 13 and the rest 16), same insets (trough 0.001 proud of the face,
 * fill 0.002) and same full-bright unshaded look. The earlier version drew plain coloured
 * quads instead - a wide orange strip where Ender IO has a thin violet one - which is what
 * made the display not read as a capacitor bank.</p>
 *
 * <p>Running Ender IO's renderer over our fake block entities is still not viable, for two
 * independent reasons: it reads {@code getEnergyStorage()}, which on the client is the
 * synced storage (max 0, so never any fill), and it gates every face on
 * {@code getDisplayMode(face)}, which defaults to {@code NONE} and resolves visibility
 * through the block entity's level - the real client level, at our scene coordinates. So it
 * draws nothing at all for our blocks; the bars below are the whole visual.</p>
 *
 * <p>Sprites are looked up by name, so nothing here needs Ender IO on the classpath; if the
 * textures are not in the atlas (Ender IO absent, or renamed) the bar is simply skipped.</p>
 */
final class EnergyBarRenderer {

    private static final ResourceLocation TROUGH = texture("capacitor_bank_bar_full");
    private static final ResourceLocation TROUGH_END = texture("capacitor_bank_bar_end");
    private static final ResourceLocation FILL = texture("capacitor_bank_bar_energy");

    /** How far proud of the block face each layer sits, as in Ender IO's renderer. */
    private static final float TROUGH_INSET = -0.001F;
    private static final float FILL_INSET = -0.002F;

    /** Fill pixels per block: the bottom one starts 3px up, so it holds 13 instead of 16. */
    private static final int PIXELS = 16;
    private static final int BOTTOM_PIXELS = 13;
    private static final float BOTTOM_OFFSET = 3F / 16F;

    private EnergyBarRenderer() {
    }

    static void draw(PoseStack pose, MultiBufferSource.BufferSource buffers,
                     int w, int h, int d, float charge) {
        TextureAtlasSprite trough = sprite(TROUGH);
        TextureAtlasSprite troughEnd = sprite(TROUGH_END);
        TextureAtlasSprite fill = sprite(FILL);
        if (trough == null || troughEnd == null || fill == null) {
            return;
        }
        VertexConsumer buffer = buffers.getBuffer(RenderType.cutout());
        // A single bar, on one face only, like a real bank: DisplayMode.BAR defaults to NONE
        // everywhere and placing the first block of a group sets it on exactly one face
        // (CapacitorBankBlock#setPlacedBy). One per block would be a wall of stripes.
        // The middle column of the -Z face: that face and +X are the two the default
        // isometric view (yaw 225) shows, and the bar sits centred on it.
        column(pose, buffer, trough, troughEnd, fill, Direction.NORTH, (w - 1) / 2, 0, h, charge);
        buffers.endBatch(RenderType.cutout());
    }

    /** One continuous bar running the full height of the column at (x, z). */
    private static void column(PoseStack pose, VertexConsumer buffer, TextureAtlasSprite trough,
                               TextureAtlasSprite troughEnd, TextureAtlasSprite fill,
                               Direction facing, int x, int z, int height, float charge) {
        // Each block draws its face in two halves; the capped sprite goes on the outer half
        // of the two end blocks, the continuous one everywhere else, so the run reads as a
        // single bar with rounded ends however tall the bank is.
        for (int y = 0; y < height; y++) {
            pose.pushPose();
            pose.translate(x, y, z);
            PoseStack.Pose at = pose.last();
            face(at, buffer, y == height - 1 ? troughEnd : trough, facing, 0.5F, 0.5F, TROUGH_INSET);
            face(at, buffer, y == 0 ? troughEnd : trough, facing, 0F, 0.5F, TROUGH_INSET);
            pose.popPose();
        }

        int pixels = Math.round(Math.clamp(charge, 0F, 1F) * (10 + (height - 1) * PIXELS));
        for (int y = 0; y < height && pixels > 0; y++) {
            boolean bottom = y == 0;
            int filled = Math.min(pixels, bottom ? BOTTOM_PIXELS : PIXELS);
            pose.pushPose();
            pose.translate(x, y, z);
            face(pose.last(), buffer, fill, facing,
                    bottom ? BOTTOM_OFFSET : 0F, filled / 16F, FILL_INSET);
            pose.popPose();
            pixels -= bottom ? BOTTOM_PIXELS : PIXELS;
        }
    }

    /**
     * A full-width quad on one side face of the current block, from height {@code y} to
     * {@code y + h}, sitting {@code z} out of the face. The UV sub-rectangle matches the
     * geometry one, which is what puts the right part of the capped sprite at the right end
     * of the run. Vertex order and winding are EnderCore's {@code RenderUtil#renderFace},
     * per face, so each side keeps an outward winding and an upright texture.
     */
    private static void face(PoseStack.Pose pose, VertexConsumer buffer, TextureAtlasSprite sprite,
                             Direction facing, float y, float h, float z) {
        switch (facing) {
            case NORTH -> quad(pose, buffer, sprite,
                    0F, 1F, y + h, y, z, z, z, z, 0F, 1F, y, y + h, 0F, 0F, -1F);
            case SOUTH -> quad(pose, buffer, sprite,
                    0F, 1F, y, y + h, 1F - z, 1F - z, 1F - z, 1F - z, 1F, 0F, y + h, y, 0F, 0F, 1F);
            case EAST -> quad(pose, buffer, sprite,
                    1F - z, 1F - z, y + h, y, 0F, 1F, 1F, 0F, 0F, 1F, y, y + h, 1F, 0F, 0F);
            case WEST -> quad(pose, buffer, sprite,
                    z, z, y, y + h, 0F, 1F, 1F, 0F, 1F, 0F, y + h, y, -1F, 0F, 0F);
            case UP, DOWN -> {
                // never: bars only live on the four side faces
            }
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, TextureAtlasSprite sprite,
                             float x0, float x1, float y0, float y1,
                             float z0, float z1, float z2, float z3,
                             float minU, float maxU, float minV, float maxV,
                             float nx, float ny, float nz) {
        vertex(pose, buffer, sprite, x0, y0, z0, minU, minV, nx, ny, nz);
        vertex(pose, buffer, sprite, x1, y0, z1, maxU, minV, nx, ny, nz);
        vertex(pose, buffer, sprite, x1, y1, z2, maxU, maxV, nx, ny, nz);
        vertex(pose, buffer, sprite, x0, y1, z3, minU, maxV, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, TextureAtlasSprite sprite,
                               float x, float y, float z, float u, float v,
                               float nx, float ny, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(-1)
                .setUv(sprite.getU(u), sprite.getV(v))
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("enderio", "block/capacitor_additionals/" + name);
    }

    /** {@code null} when the sprite is not in the block atlas (no Ender IO, renamed texture). */
    @Nullable
    private static TextureAtlasSprite sprite(ResourceLocation texture) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(texture);
        return sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? null : sprite;
    }
}
