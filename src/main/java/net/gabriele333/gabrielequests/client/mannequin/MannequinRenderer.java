package net.gabriele333.gabrielequests.client.mannequin;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import net.gabriele333.gabrielequests.client.DisplayNameTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws a {@link MannequinSpec} as a live player model in the GUI. A single fake
 * {@link RemotePlayer} is built from the local player's {@code GameProfile} (so it wears the
 * viewer's own skin, slim/classic model included) and reused across draws; its equipment is
 * set from the spec each frame and it is rendered with vanilla's
 * {@link InventoryScreen#renderEntityInInventory} - the same battle-tested path the survival
 * inventory uses, which sets up its own projection/lighting and restores GUI state.
 *
 * <p>Unlike the multiblock renderer there is no off-screen cache: one entity render per
 * frame is cheap, and it lets the mannequin animate/rotate freely. The whole thing is
 * wrapped so a render failure logs once and falls back to a placeholder box instead of
 * breaking the quest screen.</p>
 *
 * <p><b>Rotation</b> is a rigid turntable: the model's body and head share one angle
 * ({@code 180 + yaw}, the 180 turning its front to the camera) so it spins as one piece,
 * and the pitch tilts the whole view via the pose quaternion. This differs from vanilla's
 * "follow mouse" helper, whose head turns at twice the body's rate - fine for a glance,
 * wrong for a full spin.</p>
 */
public final class MannequinRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    private static AbstractClientPlayer mannequin;
    private static ClientLevel mannequinLevel;
    private static boolean failed;
    private static boolean loggedDraw;

    private MannequinRenderer() {
    }

    public static void render(GuiGraphics graphics, int x, int y, int w, int h,
                              MannequinSpec spec, int yaw, int pitch) {
        Minecraft mc = Minecraft.getInstance();
        AbstractClientPlayer entity = mc.level != null && mc.player != null ? mannequin(mc) : null;
        if (entity == null) {
            Color4I.rgb(0x20_20_20).withAlpha(140).draw(graphics, x, y, w, h);
            return;
        }
        try {
            entity.setItemSlot(EquipmentSlot.HEAD, stackOf(spec.head()));
            entity.setItemSlot(EquipmentSlot.CHEST, stackOf(spec.chest()));
            entity.setItemSlot(EquipmentSlot.LEGS, stackOf(spec.legs()));
            entity.setItemSlot(EquipmentSlot.FEET, stackOf(spec.feet()));
            entity.setItemSlot(EquipmentSlot.MAINHAND, stackOf(spec.mainHand()));
            entity.setItemSlot(EquipmentSlot.OFFHAND, stackOf(spec.offHand()));

            // Rigid turntable: body and head aligned so the whole model spins together
            // (render uses partialTick 1.0, so only the current angles matter, not the *O
            // "previous tick" fields). Yaw is negated so a drag spins it the same way as the
            // display multiblock (whose yaw feeds a pose rotateY of opposite handedness).
            float facing = 180F - yaw;
            entity.yBodyRot = facing;
            entity.setYRot(facing);
            entity.yHeadRot = facing;
            entity.setXRot(0F);

            // Framing: centre of the icon box (FTB draws icons as a 1x1 box), entity lifted by
            // half its height so it sits centred, then sized to fill ~76% of the box height
            // (undistorted, see renderEntity). Pitch tilts the whole model (and the light),
            // around that same half-height point - the model's centre, not its feet.
            float cx = x + w / 2F;
            float cy = y + h / 2F;
            Vector3f translate = new Vector3f(0F, entity.getBbHeight() / 2F + 0.0625F, 0F);
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(pitch));
            pose.mul(tilt);

            renderEntity(graphics, mc, entity, cx, cy, 0.42F, translate, pose, tilt);
        } catch (Throwable t) {
            if (!failed) {
                failed = true;
                LOGGER.error("[GabrieleQuests/QuestsTools] Failed to render mannequin {} - using placeholder",
                        spec.serialize(), t);
            }
            Color4I.rgb(0x20_20_20).withAlpha(140).draw(graphics, x, y, w, h);
        }
    }

    /**
     * Mirrors {@link InventoryScreen#renderEntityInInventory} but wraps the pose / dispatcher
     * state in try/finally, so an armour or item renderer that throws mid-render cannot leave
     * the GUI pose stack unbalanced (the caller then catches and draws the placeholder), and
     * cancels FTB's non-uniform icon scaling so the model stays undistorted. The
     * camera-orientation override mirrors vanilla exactly; the order of the framing translate
     * does not - see the pivot note below. {@code translate} is the model's centre in entity
     * space, which is both where it gets pushed to and what the tilt turns around.
     */
    private static void renderEntity(GuiGraphics graphics, Minecraft mc, AbstractClientPlayer entity,
                                     float cx, float cy, float fillHeight, Vector3f translate,
                                     Quaternionf pose, Quaternionf cameraTilt) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        PoseStack poseStack = graphics.pose();
        // No scissor here: FTB draws the icon inside an already-transformed pose, so the
        // x/y/w/h are LOCAL coordinates, not framebuffer pixels - an enableScissor with them
        // would clip the whole mannequin away. The entity composes with the active pose.
        poseStack.pushPose();
        try {
            // FTB scaled the 1x1 icon box to the image's width x height (non-uniformly for a
            // portrait), which would squash the 3D model. Read those axis scales back off the
            // pose and cancel them (t/sx, t/sy) so the render is undistorted whatever the
            // image shape; t fills ~fillHeight of the box height. Z uses -t directly because
            // FTB's Z scale is 1 (it only scaled x/y).
            Matrix4f m = poseStack.last().pose();
            float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
            float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12());
            if (sx < 1e-4F) {
                sx = 1F;
            }
            if (sy < 1e-4F) {
                sy = 1F;
            }
            float t = fillHeight * sy;
            if (!loggedDraw) {
                loggedDraw = true;
                LOGGER.info("[GabrieleQuests/QuestsTools] Mannequin render: sx={} sy={} t={} bbH={}",
                        sx, sy, t, entity.getBbHeight());
            }
            poseStack.translate(cx, cy, 50F);
            poseStack.scale(t / sx, t / sy, -t);
            // Rotate first, then push the model down by its own centre: that makes the centre
            // the fixed point of the tilt. Vanilla's helper does it the other way round
            // (translate, then rotate), which leaves the entity's ORIGIN - its feet - as the
            // pivot, so a tilted model swings out of the icon box instead of turning in place.
            // At pitch 0 the two orders are the same matrix (the 180 degree Z flip turns
            // -centre into +centre), so nothing moves for an untilted display.
            poseStack.mulPose(pose);
            poseStack.translate(-translate.x, -translate.y, -translate.z);
            Lighting.setupForEntityInInventory();
            dispatcher.overrideCameraOrientation(
                    cameraTilt.conjugate(new Quaternionf()).rotateY((float) Math.PI));
            dispatcher.setRenderShadow(false);
            // No name plate over a decoration: a player's is drawn unconditionally, so it has
            // to be turned off from the render event. See DisplayNameTags.
            DisplayNameTags.begin(entity);
            RenderSystem.runAsFancy(() -> dispatcher.render(entity, 0D, 0D, 0D, 0F, 1F,
                    poseStack, graphics.bufferSource(), LightTexture.FULL_BRIGHT));
            graphics.flush();
        } finally {
            DisplayNameTags.end();
            dispatcher.setRenderShadow(true);
            dispatcher.overrideCameraOrientation((Quaternionf) null);
            Lighting.setupFor3DItems();
            poseStack.popPose();
        }
    }

    /** The shared fake player, rebuilt if the client level changed (world reload). */
    private static AbstractClientPlayer mannequin(Minecraft mc) {
        ClientLevel level = mc.level;
        if (mannequin == null || mannequinLevel != level) {
            try {
                mannequin = new RemotePlayer(level, mc.player.getGameProfile());
                mannequinLevel = level;
            } catch (Throwable t) {
                LOGGER.error("[GabrieleQuests/QuestsTools] Could not create the mannequin entity", t);
                mannequin = null;
                mannequinLevel = null;
            }
        }
        return mannequin;
    }

    /** Resolves an item id to a single stack; empty for blank ids or absent items/mods. */
    private static ItemStack stackOf(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(rl));
    }
}
