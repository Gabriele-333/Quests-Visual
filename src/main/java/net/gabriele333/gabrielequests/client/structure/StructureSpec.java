package net.gabriele333.gabrielequests.client.structure;

import org.jetbrains.annotations.Nullable;

/**
 * The serialisable description of a display structure: which structure template to show,
 * the persisted orbit angles (yaw/pitch, exactly like the display multiblock, mannequin and
 * entity), whether non-editors may spin it, and whether world-gen marker blocks are drawn.
 * This is the whole persisted state; the blocks themselves are read from the {@code .nbt}
 * template on demand and cached, never stored here.
 *
 * <p>The string form is
 * {@code gabrielequests-structure:id=<namespace:path>,rot=<yaw>x<pitch>,view=<0|1>,markers=<0|1>}.
 * Like the other display specs it must survive FTB Library's icon-string handling, so it
 * contains neither {@code ";"} (property separator in {@code Icon.getIcon}) nor
 * {@code " + "} (combined-icon separator). Structure ids are
 * {@code namespace:path/with/slashes} and never contain {@code ","}/{@code "="}/spaces, so
 * the field separators are safe. Unknown keys are dropped on parse and a structure that the
 * current mod set does not have is simply rendered as a placeholder, so a display structure
 * round-trips even across differing mod sets.</p>
 *
 * <p>This record is deliberately common-safe (no client classes): only {@code String},
 * {@code int} and {@code boolean} fields, so a dedicated server can read and re-serialise
 * it. {@link #parse} never throws; on malformed input the caller keeps the raw string
 * untouched.</p>
 */
public record StructureSpec(boolean whole, String structureId, int yaw, int pitch,
                            boolean viewRotate, boolean markers) {

    public static final String PREFIX = "gabrielequests-structure:";
    /** The classic isometric view, same as the display multiblock. */
    public static final int DEFAULT_YAW = 225;
    public static final int DEFAULT_PITCH = 30;
    /**
     * Shown until the author picks something. Vanilla ships it, so it exists in every
     * install; {@link StructureIndex#defaultStructure()} falls back to whatever is
     * available should that ever stop being true.
     */
    public static final String DEFAULT_STRUCTURE = "minecraft:igloo/top";

    /** A default display structure (isometric view, no view-rotate, markers hidden). */
    public static StructureSpec defaultSpec() {
        return new StructureSpec(false, StructureIndex.defaultStructure(), DEFAULT_YAW,
                DEFAULT_PITCH, false, false);
    }

    /** Sanitises the structure id and wraps/clamps the view angles. */
    public StructureSpec normalized() {
        return new StructureSpec(whole, clean(structureId), Math.floorMod(yaw, 360),
                Math.clamp(pitch, -89, 89), viewRotate, markers);
    }

    /** Drops nulls and anything that would break the serialisation separators. */
    private static String clean(@Nullable String id) {
        if (id == null || id.isEmpty() || id.contains(",") || id.contains("=") || id.contains(";")
                || id.contains(" ") || id.contains("+")) {
            return DEFAULT_STRUCTURE;
        }
        return id;
    }

    /** Switches to another structure, saying whether it is a whole one or a single piece. */
    public StructureSpec withStructure(boolean isWhole, String id) {
        return new StructureSpec(isWhole, id, yaw, pitch, viewRotate, markers).normalized();
    }

    public StructureSpec withRotation(int newYaw, int newPitch) {
        return new StructureSpec(whole, structureId, newYaw, newPitch, viewRotate, markers).normalized();
    }

    public StructureSpec withViewRotate(boolean v) {
        return new StructureSpec(whole, structureId, yaw, pitch, v, markers).normalized();
    }

    public StructureSpec withMarkers(boolean m) {
        return new StructureSpec(whole, structureId, yaw, pitch, viewRotate, m).normalized();
    }

    public String serialize() {
        return PREFIX + "kind=" + (whole ? "whole" : "piece") + ",id=" + structureId
                + ",rot=" + yaw + "x" + pitch
                + ",view=" + (viewRotate ? 1 : 0) + ",markers=" + (markers ? 1 : 0);
    }

    /** Parses and normalises a serialised spec; {@code null} if not ours or malformed. */
    @Nullable
    public static StructureSpec parse(String s) {
        if (s == null || !s.startsWith(PREFIX)) {
            return null;
        }
        try {
            String body = s.substring(PREFIX.length());
            String id = DEFAULT_STRUCTURE;
            int yaw = DEFAULT_YAW;
            int pitch = DEFAULT_PITCH;
            boolean viewRotate = false;
            boolean markers = false;
            // Strings written before whole structures existed have no "kind" and are pieces.
            boolean whole = false;
            for (String part : body.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                switch (kv[0]) {
                    case "kind" -> whole = kv[1].equals("whole");
                    case "id" -> id = kv[1];
                    case "rot" -> {
                        String[] rot = kv[1].split("x");
                        if (rot.length == 2) {
                            yaw = Integer.parseInt(rot[0]);
                            pitch = Integer.parseInt(rot[1]);
                        }
                    }
                    case "view" -> viewRotate = kv[1].equals("1");
                    case "markers" -> markers = kv[1].equals("1");
                    default -> {
                    }
                }
            }
            return new StructureSpec(whole, id, yaw, pitch, viewRotate, markers).normalized();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
