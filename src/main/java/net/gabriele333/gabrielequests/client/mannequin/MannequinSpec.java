package net.gabriele333.gabrielequests.client.mannequin;

import org.jetbrains.annotations.Nullable;

/**
 * The serialisable description of a display mannequin: the six equipment slots (as item
 * registry ids, empty string = nothing) plus the persisted orbit angles. This is the whole
 * persisted state; the rendered player model (skin, pose, armour/weapon models) is derived
 * from it on the client and never stored.
 *
 * <p>The string form is
 * {@code gabrielequests-mannequin:head=<id>,chest=<id>,legs=<id>,feet=<id>,main=<id>,off=<id>,rot=<yaw>x<pitch>}.
 * Like {@link net.gabriele333.gabrielequests.client.multiblock.MultiblockSpec} it must
 * survive FTB Library's icon-string handling, so it contains neither {@code ";"} nor
 * {@code " + "}; item ids never contain {@code ","}/{@code "="}/spaces, so the field
 * separators are safe. Unknown keys are dropped on parse and unknown/absent mod items are
 * simply rendered as empty slots, so a mannequin round-trips even across differing mod
 * sets.</p>
 *
 * <p>This record is deliberately common-safe (no client classes): only {@code String}/
 * {@code int} fields, so the server can read and re-serialise it. {@link #parse} never
 * throws; on malformed input the caller keeps the raw string untouched.</p>
 */
public record MannequinSpec(String head, String chest, String legs, String feet,
                            String mainHand, String offHand, int yaw, int pitch) {

    public static final String PREFIX = "gabrielequests-mannequin:";
    /**
     * Legacy prefix from when this feature was embedded in the FMTT2 mod. {@link #parse}
     * still reads it so mannequins authored by old FMTT2 keep working; the icon always
     * re-serialises with {@link #PREFIX}, so re-saving a chapter migrates it (one-way).
     */
    public static final String LEGACY_PREFIX = "fmtt2-mannequin:";
    /** Front-facing (the renderer adds the 180° needed to face the camera) and level. */
    public static final int DEFAULT_YAW = 0;
    public static final int DEFAULT_PITCH = 0;

    /** An empty mannequin (bare player, default view). */
    public static MannequinSpec empty() {
        return new MannequinSpec("", "", "", "", "", "", DEFAULT_YAW, DEFAULT_PITCH);
    }

    /** Sanitises the item ids and wraps/clamps the view angles. */
    public MannequinSpec normalized() {
        return new MannequinSpec(clean(head), clean(chest), clean(legs), clean(feet),
                clean(mainHand), clean(offHand),
                Math.floorMod(yaw, 360), Math.clamp(pitch, -89, 89));
    }

    /** Drops nulls and anything that would break the serialisation separators. */
    private static String clean(@Nullable String id) {
        if (id == null || id.contains(",") || id.contains("=") || id.contains(";")
                || id.contains(" ") || id.contains("+")) {
            return "";
        }
        return id;
    }

    public MannequinSpec withHead(String id) {
        return new MannequinSpec(id, chest, legs, feet, mainHand, offHand, yaw, pitch).normalized();
    }

    public MannequinSpec withChest(String id) {
        return new MannequinSpec(head, id, legs, feet, mainHand, offHand, yaw, pitch).normalized();
    }

    public MannequinSpec withLegs(String id) {
        return new MannequinSpec(head, chest, id, feet, mainHand, offHand, yaw, pitch).normalized();
    }

    public MannequinSpec withFeet(String id) {
        return new MannequinSpec(head, chest, legs, id, mainHand, offHand, yaw, pitch).normalized();
    }

    public MannequinSpec withMainHand(String id) {
        return new MannequinSpec(head, chest, legs, feet, id, offHand, yaw, pitch).normalized();
    }

    public MannequinSpec withOffHand(String id) {
        return new MannequinSpec(head, chest, legs, feet, mainHand, id, yaw, pitch).normalized();
    }

    public MannequinSpec withRotation(int newYaw, int newPitch) {
        return new MannequinSpec(head, chest, legs, feet, mainHand, offHand, newYaw, newPitch).normalized();
    }

    public String serialize() {
        return PREFIX + "head=" + head + ",chest=" + chest + ",legs=" + legs + ",feet=" + feet
                + ",main=" + mainHand + ",off=" + offHand + ",rot=" + yaw + "x" + pitch;
    }

    /** Parses and normalises a serialised spec; {@code null} if not ours or malformed. */
    @Nullable
    public static MannequinSpec parse(String s) {
        if (s == null) {
            return null;
        }
        String body;
        if (s.startsWith(PREFIX)) {
            body = s.substring(PREFIX.length());
        } else if (s.startsWith(LEGACY_PREFIX)) {
            body = s.substring(LEGACY_PREFIX.length());
        } else {
            return null;
        }
        try {
            String head = "", chest = "", legs = "", feet = "", main = "", off = "";
            int yaw = DEFAULT_YAW;
            int pitch = DEFAULT_PITCH;
            for (String part : body.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                switch (kv[0]) {
                    case "head" -> head = kv[1];
                    case "chest" -> chest = kv[1];
                    case "legs" -> legs = kv[1];
                    case "feet" -> feet = kv[1];
                    case "main" -> main = kv[1];
                    case "off" -> off = kv[1];
                    case "rot" -> {
                        String[] rot = kv[1].split("x");
                        if (rot.length == 2) {
                            yaw = Integer.parseInt(rot[0]);
                            pitch = Integer.parseInt(rot[1]);
                        }
                    }
                    default -> {
                    }
                }
            }
            return new MannequinSpec(head, chest, legs, feet, main, off, yaw, pitch).normalized();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
