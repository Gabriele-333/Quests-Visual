package net.gabriele333.gabrielequests.client.entity;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import net.gabriele333.gabrielequests.client.DisplayNameTags;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws an {@link EntitySpec} as a live 3D entity model in the GUI. The entity is built once
 * from its registered {@link EntityType} in the client level and cached (rebuilt only when
 * the chosen type, speed or the level changes), then rendered every frame with the vanilla
 * {@link EntityRenderDispatcher} - the same path the survival inventory uses for the player,
 * generalised to any entity.
 *
 * <p><b>Animation.</b> So that animated entities actually move (the ender dragon flapping,
 * flying mobs beating their wings, idle breathing, ...), a cached <em>living</em> entity is
 * ticked on a wall-clock schedule before each render, scaled by the spec's speed multiplier
 * (1.0 = real-time, 0 = paused), catch-up capped; the render uses the matching partial tick
 * for smoothness. Ticking runs on the client level, so mob AI ({@code isEffectiveAi} is
 * server-only) and ambient sounds ({@code !isClientSide}) never fire; the entity is pinned at
 * the origin with gravity off each tick so it cannot drift. Non-living entities are not ticked
 * (many self-discard - TNT, arrows, falling blocks) and simply render statically. Any tick
 * failure disables ticking for that entity and it falls back to a static pose.</p>
 *
 * <p>Everything is wrapped so that a renderer that throws (some entities need world state a
 * GUI cannot provide) logs once and falls back to a placeholder box instead of breaking the
 * quest screen; an entity type that cannot be created (e.g. the player) degrades the same
 * way.</p>
 *
 * <p><b>Rotation</b> is a rigid yaw/pitch turntable, exactly like the display multiblock and
 * mannequin: body and head share one angle ({@code 180 - yaw}, the 180 turning the front to
 * the camera and the negation matching the multiblock's spin direction) so the model turns as
 * one piece, and the pitch tilts the whole view through the pose quaternion.</p>
 *
 * <p><b>Framing</b> is size-aware: the render scale is derived from the entity's bounding
 * box so a silverfish and an iron golem both fill roughly the same fraction of the icon,
 * and the model is lifted by half its height to sit centred in the box - which is also the
 * point the pitch turns around, so a tilted entity spins in place instead of swinging around
 * its feet the way vanilla's inventory helper would.</p>
 */
public final class EntityDisplayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Fraction of the icon-box height the largest bounding-box axis should span. */
    private static final float TARGET_FILL = 0.82F;
    /** Real-time epoch the animation clock counts ticks from (20 ticks/s at speed 1.0). */
    private static final long START_MS = System.currentTimeMillis();
    /** Cap on ticks advanced per frame, so a long-idle screen never triggers a tick spiral. */
    private static final int MAX_CATCHUP = 8;

    /**
     * One display per (entity id, speed) pair - so several icons of the same entity share one
     * instance, but two speeds get their own so each animates at its own rate. Cleared
     * wholesale when the client level changes, because every cached entity holds a reference
     * to the old level.
     */
    private static final Map<String, Display> CACHE = new HashMap<>();

    private static ClientLevel cacheLevel;
    private static boolean loggedRenderError;
    private static boolean loggedTickError;

    private EntityDisplayRenderer() {
    }

    /** A cached entity plus its animation bookkeeping. */
    private static final class Display {
        @Nullable
        final Entity entity;
        final double speed;
        boolean tick;   // advance animation by ticking this entity
        long animTick;  // (speed-scaled) wall-clock tick the entity has been advanced to

        Display(@Nullable Entity entity, double speed) {
            this.entity = entity;
            this.speed = speed;
            this.tick = entity instanceof LivingEntity && speed > 0D;
            this.animTick = targetTicks(speed);
        }
    }

    public static void render(GuiGraphics graphics, int x, int y, int w, int h,
                              EntitySpec spec, int yaw, int pitch) {
        Minecraft mc = Minecraft.getInstance();
        Display display = mc.level != null ? display(mc, spec.entityId(), spec.speed()) : null;
        Entity entity = display != null ? display.entity : null;
        if (entity == null) {
            placeholder(graphics, x, y, w, h);
            return;
        }
        try {
            // Advance the animation to the current wall-clock time before drawing this frame.
            animate(display);

            // Rigid turntable: body and head aligned so the whole model turns together. Yaw is
            // negated (facing = 180 - yaw) so a drag spins it the same way as the multiblock.
            float facing = 180F - yaw;
            entity.setYRot(facing);
            entity.setXRot(0F);
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = facing;
                living.yBodyRotO = facing;
                living.yHeadRot = facing;
                living.yHeadRotO = facing;
                living.setYRot(facing);
                living.setXRot(0F);
            }

            // Size-aware framing: fill ~TARGET_FILL of the box regardless of the entity's
            // real dimensions, and lift it by half its height so it sits centred.
            float bbH = entity.getBbHeight();
            float bbW = entity.getBbWidth();
            float worldSize = Math.max(bbH, bbW);
            if (worldSize < 0.1F) {
                worldSize = 1F;
            }
            float fillHeight = TARGET_FILL / worldSize;

            float cx = x + w / 2F;
            float cy = y + h / 2F;
            Vector3f translate = new Vector3f(0F, bbH / 2F, 0F);
            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf tilt = new Quaternionf().rotateX((float) Math.toRadians(pitch));
            pose.mul(tilt);

            renderEntity(graphics, mc, entity, cx, cy, fillHeight, translate, pose, tilt,
                    animPartialTick(display.speed));
        } catch (Throwable t) {
            if (!loggedRenderError) {
                loggedRenderError = true;
                LOGGER.error("[GabrieleQuests/QuestsTools] Failed to render display entity {} - using placeholder",
                        spec.serialize(), t);
            }
            placeholder(graphics, x, y, w, h);
        }
    }

    /**
     * Ticks the entity forward to the current (speed-scaled) wall-clock time (idempotent within
     * a frame, so the same entity shown by several icons only advances once). Pins it at the
     * origin with gravity off before each tick so it animates in place instead of falling, and
     * disables ticking for good if it ever throws.
     */
    private static void animate(Display display) {
        Entity entity = display.entity;
        if (entity == null || !display.tick) {
            return;
        }
        long target = targetTicks(display.speed);
        long steps = Math.min(target - display.animTick, MAX_CATCHUP);
        display.animTick = target;
        if (steps <= 0) {
            return;
        }
        try {
            for (long i = 0; i < steps; i++) {
                pinAtOrigin(entity);
                entity.tick();
            }
            pinAtOrigin(entity);
        } catch (Throwable t) {
            display.tick = false;
            if (!loggedTickError) {
                loggedTickError = true;
                LOGGER.warn("[GabrieleQuests/QuestsTools] Display entity {} cannot be animated - showing it static",
                        entity.getType(), t);
            }
        }
    }

    /** Resets the entity's position, motion and fall state so a tick animates it in place. */
    private static void pinAtOrigin(Entity entity) {
        entity.setDeltaMovement(0D, 0D, 0D);
        entity.setPos(0D, 0D, 0D);
        entity.setOldPosAndRot();
        entity.fallDistance = 0F;
    }

    /**
     * Mirrors {@code InventoryScreen#renderEntityInInventory} (the same path the mannequin
     * uses) but for a generic entity, wrapping the pose / dispatcher state in try/finally so a
     * renderer that throws mid-render cannot leave the GUI pose stack unbalanced, and
     * cancelling FTB's non-uniform icon scaling so the model stays undistorted. It also
     * deviates from vanilla in the order of the framing translate - see the pivot note below.
     * {@code translate} is the model's centre in entity space, which is both where it gets
     * pushed to and what the tilt turns around.
     */
    private static void renderEntity(GuiGraphics graphics, Minecraft mc, Entity entity,
                                     float cx, float cy, float fillHeight, Vector3f translate,
                                     Quaternionf pose, Quaternionf cameraTilt, float partialTick) {
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        try {
            // FTB scaled the 1x1 icon box to the image's width x height (non-uniformly for a
            // non-square image), which would squash the 3D model. Read those axis scales back
            // off the pose and cancel them (t/sx, t/sy) so the render is undistorted whatever
            // the image shape; t fills ~fillHeight of the box height. Z uses -t directly
            // because FTB's Z scale is 1 (it only scaled x/y).
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
            DisplayNameTags.begin(entity);
            RenderSystem.runAsFancy(() -> dispatcher.render(entity, 0D, 0D, 0D, 0F, partialTick,
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

    /** The cached display for this (id, speed), (re)built if absent, removed, or level changed. */
    private static Display display(Minecraft mc, String id, float speed) {
        ClientLevel level = mc.level;
        if (cacheLevel != level) {
            CACHE.clear();
            cacheLevel = level;
        }
        String key = id + "@" + speed;
        Display display = CACHE.get(key);
        if (display == null || (display.entity != null && display.entity.isRemoved())) {
            display = new Display(create(level, id), speed);
            CACHE.put(key, display);
        }
        return display;
    }

    /** Creates a fresh entity of the given type in the level, or {@code null} if it cannot be. */
    @Nullable
    private static Entity create(ClientLevel level, String id) {
        EntityType<?> type = type(id);
        if (type == null) {
            return null;
        }
        try {
            Entity entity = type.create(level);
            if (entity != null) {
                entity.setPos(0D, 0D, 0D);
                entity.setNoGravity(true);
            }
            return entity;
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Could not create display entity {}", id, t);
            return null;
        }
    }

    /** The display name of an entity type ("Allay", ...), or the raw id if unknown. */
    public static Component displayName(String id) {
        EntityType<?> type = type(id);
        return type != null ? type.getDescription() : Component.literal(id);
    }

    /** The entity type's translation key ("entity.minecraft.allay"), or the raw id if unknown. */
    public static String descriptionKey(String id) {
        EntityType<?> type = type(id);
        return type != null ? type.getDescriptionId() : id;
    }

    @Nullable
    private static EntityType<?> type(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null ? BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null) : null;
    }

    /** Whole animation ticks elapsed, scaled by the speed multiplier. */
    private static long targetTicks(double speed) {
        return (long) ((System.currentTimeMillis() - START_MS) * speed / 50.0D);
    }

    /** Fractional part of the current (speed-scaled) tick, for smooth interpolation. */
    private static float animPartialTick(double speed) {
        double ticks = (System.currentTimeMillis() - START_MS) * speed / 50.0D;
        return (float) (ticks - Math.floor(ticks));
    }

    private static void placeholder(GuiGraphics graphics, int x, int y, int w, int h) {
        Color4I.rgb(0x20_20_20).withAlpha(140).draw(graphics, x, y, w, h);
    }
}
