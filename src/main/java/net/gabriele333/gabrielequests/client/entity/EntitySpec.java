package net.gabriele333.gabrielequests.client.entity;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The serialisable description of a display entity: the registry id of the entity type to
 * show, the persisted orbit angles (yaw/pitch, exactly like the display multiblock and
 * mannequin) and the animation-speed multiplier. This is the whole persisted state; the live
 * entity (built from the type in the client level) and everything about its rendering are
 * derived from it and never stored.
 *
 * <p>The string form is
 * {@code gabrielequests-entity:id=<entity id>,rot=<yaw>x<pitch>,speed=<speed>}. Like
 * {@link net.gabriele333.gabrielequests.client.mannequin.MannequinSpec} it must survive FTB
 * Library's icon-string handling, so it contains neither {@code ";"} nor {@code " + "};
 * entity ids never contain {@code ","}/{@code "="}/spaces and the speed is a plain
 * {@code %.2f} decimal, so the field separators are safe. Unknown keys are dropped on parse
 * and an unknown/absent entity type is simply rendered as a placeholder, so a display entity
 * round-trips even across differing mod sets.</p>
 *
 * <p>This record is deliberately common-safe (no client classes): only {@code String}/
 * {@code int}/{@code float} fields, so a dedicated server can read and re-serialise it.
 * {@link #parse} never throws; on malformed input the caller keeps the raw string
 * untouched.</p>
 */
public record EntitySpec(String entityId, int yaw, int pitch, float speed) {

    public static final String PREFIX = "gabrielequests-entity:";
    /** Front-facing (the renderer adds the 180° needed to face the camera) and level. */
    public static final int DEFAULT_YAW = 0;
    public static final int DEFAULT_PITCH = 0;
    /** 1.0 = real-time; 0 = paused. */
    public static final float DEFAULT_SPEED = 1.0F;
    public static final float MAX_SPEED = 4.0F;
    /** Shown until the author picks something; every instance has this entity type. */
    public static final String DEFAULT_ENTITY = "minecraft:allay";

    /** A default display entity (a default entity type, front view, real-time speed). */
    public static EntitySpec defaultSpec() {
        return new EntitySpec(DEFAULT_ENTITY, DEFAULT_YAW, DEFAULT_PITCH, DEFAULT_SPEED);
    }

    /** Sanitises the entity id, wraps/clamps the view angles and clamps the speed. */
    public EntitySpec normalized() {
        float s = Float.isNaN(speed) ? DEFAULT_SPEED : Math.clamp(speed, 0F, MAX_SPEED);
        return new EntitySpec(clean(entityId),
                Math.floorMod(yaw, 360), Math.clamp(pitch, -89, 89), s);
    }

    /** Drops nulls and anything that would break the serialisation separators. */
    private static String clean(@Nullable String id) {
        if (id == null || id.contains(",") || id.contains("=") || id.contains(";")
                || id.contains(" ") || id.contains("+")) {
            return DEFAULT_ENTITY;
        }
        return id;
    }

    public EntitySpec withEntity(String id) {
        return new EntitySpec(id, yaw, pitch, speed).normalized();
    }

    public EntitySpec withRotation(int newYaw, int newPitch) {
        return new EntitySpec(entityId, newYaw, newPitch, speed).normalized();
    }

    public EntitySpec withSpeed(float newSpeed) {
        return new EntitySpec(entityId, yaw, pitch, newSpeed).normalized();
    }

    public String serialize() {
        return PREFIX + "id=" + entityId + ",rot=" + yaw + "x" + pitch
                + ",speed=" + String.format(Locale.ROOT, "%.2f", speed);
    }

    /** Parses and normalises a serialised spec; {@code null} if not ours or malformed. */
    @Nullable
    public static EntitySpec parse(String s) {
        if (s == null || !s.startsWith(PREFIX)) {
            return null;
        }
        try {
            String body = s.substring(PREFIX.length());
            String id = DEFAULT_ENTITY;
            int yaw = DEFAULT_YAW;
            int pitch = DEFAULT_PITCH;
            float speed = DEFAULT_SPEED;
            for (String part : body.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                switch (kv[0]) {
                    case "id" -> id = kv[1];
                    case "rot" -> {
                        String[] rot = kv[1].split("x");
                        if (rot.length == 2) {
                            yaw = Integer.parseInt(rot[0]);
                            pitch = Integer.parseInt(rot[1]);
                        }
                    }
                    case "speed" -> speed = Float.parseFloat(kv[1]);
                    default -> {
                    }
                }
            }
            return new EntitySpec(id, yaw, pitch, speed).normalized();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
