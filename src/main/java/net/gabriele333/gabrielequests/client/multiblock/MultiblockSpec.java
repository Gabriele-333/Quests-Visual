package net.gabriele333.gabrielequests.client.multiblock;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The serialisable description of a displayed multiblock: type + exterior dimensions +
 * glass walls + view-rotate flag + orbit angles + per-type block options (e.g. induction
 * cell tier, fission core layout). This is the whole persisted state of a display
 * multiblock; everything else (block layout, rendering) is derived from it and cached.
 *
 * <p>The string form is
 * {@code gabrielequests-multiblock:<type>,<w>x<h>x<d>,glass=<0|1>,view=<0|1>,rot=<yaw>x<pitch>[,<option>=<choice>...]}
 * (angles in whole degrees; older strings without {@code view}/{@code rot} default to
 * no-view-rotate and the classic isometric 225/30 view). It must survive FTB Library's
 * icon-string handling, so it contains neither {@code ";"} (property separator in
 * {@code Icon.getIcon}) nor {@code " + "} (combined-icon separator) - and it round-trips
 * through SNBT quest files and chapter network sync as a plain short string. Unknown keys
 * are dropped on parse, so older strings load fine.</p>
 *
 * <p>{@link #parse} and every {@code withX} go through {@link #normalized}, which clamps
 * the dimensions per type and reduces {@link #options} to exactly the type's declared
 * option ids with valid choices ({@link MultiblockType.Option}) - so any spec that exists
 * in memory describes a structure that is valid for its mod, and its serialised form is
 * complete and deterministic (stable render-cache key). Parsing failures yield
 * {@code null} and the caller keeps the raw string untouched (no data loss).</p>
 */
public record MultiblockSpec(MultiblockType type, int width, int height, int depth, boolean glass,
                             boolean viewRotate, int yaw, int pitch, Map<String, String> options) {

    public static final String PREFIX = "gabrielequests-multiblock:";
    /**
     * Legacy prefix from when this feature was embedded in the FMTT2 mod. {@link #parse}
     * still reads it so display multiblocks authored by old FMTT2 keep working; the icon
     * always re-serialises with {@link #PREFIX}, so opening and re-saving a chapter migrates
     * the string to the new form (one-way, no data loss).
     */
    public static final String LEGACY_PREFIX = "fmtt2-multiblock:";
    public static final int DEFAULT_YAW = 225;
    public static final int DEFAULT_PITCH = 30;

    /** Convenience constructor with the default orientation and no view-rotate. */
    public MultiblockSpec(MultiblockType type, int width, int height, int depth, boolean glass,
                          Map<String, String> options) {
        this(type, width, height, depth, glass, false, DEFAULT_YAW, DEFAULT_PITCH, options);
    }

    /** The chosen (already validated) value of a per-type option. */
    public String option(String id) {
        String value = options.get(id);
        if (value != null) {
            return value;
        }
        for (MultiblockType.Option option : type.options()) {
            if (option.id().equals(id)) {
                return option.defaultChoice();
            }
        }
        return "";
    }

    /**
     * Clamps dimensions, wraps/clamps the view angles and materialises every option of
     * the type to a valid choice.
     */
    public MultiblockSpec normalized() {
        MultiblockSpec clamped = type.clamp(this);
        Map<String, String> valid = new LinkedHashMap<>();
        for (MultiblockType.Option option : type.options()) {
            String value = options.get(option.id());
            // List.of() lists throw NPE on contains(null), so the null check is load-bearing.
            valid.put(option.id(), value != null && option.choices().contains(value)
                    ? value : option.defaultChoice());
        }
        return new MultiblockSpec(type, clamped.width(), clamped.height(), clamped.depth(),
                clamped.glass(), viewRotate, Math.floorMod(yaw, 360), Math.clamp(pitch, -89, 89),
                Map.copyOf(valid));
    }

    public MultiblockSpec withType(MultiblockType newType) {
        return new MultiblockSpec(newType, width, height, depth, glass, viewRotate, yaw, pitch, options).normalized();
    }

    public MultiblockSpec withWidth(int w) {
        return new MultiblockSpec(type, w, height, depth, glass, viewRotate, yaw, pitch, options).normalized();
    }

    public MultiblockSpec withHeight(int h) {
        return new MultiblockSpec(type, width, h, depth, glass, viewRotate, yaw, pitch, options).normalized();
    }

    public MultiblockSpec withDepth(int d) {
        return new MultiblockSpec(type, width, height, d, glass, viewRotate, yaw, pitch, options).normalized();
    }

    public MultiblockSpec withGlass(boolean g) {
        return new MultiblockSpec(type, width, height, depth, g, viewRotate, yaw, pitch, options).normalized();
    }

    public MultiblockSpec withViewRotate(boolean v) {
        return new MultiblockSpec(type, width, height, depth, glass, v, yaw, pitch, options).normalized();
    }

    public MultiblockSpec withRotation(int newYaw, int newPitch) {
        return new MultiblockSpec(type, width, height, depth, glass, viewRotate, newYaw, newPitch, options).normalized();
    }

    public MultiblockSpec withOption(String id, String choice) {
        Map<String, String> map = new HashMap<>(options);
        map.put(id, choice);
        return new MultiblockSpec(type, width, height, depth, glass, viewRotate, yaw, pitch, map).normalized();
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder(PREFIX)
                .append(type.id()).append(',')
                .append(width).append('x').append(height).append('x').append(depth)
                .append(",glass=").append(glass ? 1 : 0)
                .append(",view=").append(viewRotate ? 1 : 0)
                .append(",rot=").append(yaw).append('x').append(pitch);
        // Iterate the type's declared order, not the map's, for a stable string.
        for (MultiblockType.Option option : type.options()) {
            sb.append(',').append(option.id()).append('=').append(option(option.id()));
        }
        return sb.toString();
    }

    /**
     * Parses and normalises a serialised spec; {@code null} if the string is not ours or
     * is malformed (unknown type, garbage numbers). Never throws.
     */
    @Nullable
    public static MultiblockSpec parse(String s) {
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
            String[] parts = body.split(",");
            MultiblockType type = MultiblockType.byId(parts[0]);
            if (type == null || parts.length < 2) {
                return null;
            }
            String[] dims = parts[1].split("x");
            if (dims.length != 3) {
                return null;
            }
            int w = Integer.parseInt(dims[0]);
            int h = Integer.parseInt(dims[1]);
            int d = Integer.parseInt(dims[2]);
            boolean glass = true;
            boolean viewRotate = false;
            int yaw = DEFAULT_YAW;
            int pitch = DEFAULT_PITCH;
            Map<String, String> options = new HashMap<>();
            for (int i = 2; i < parts.length; i++) {
                String[] kv = parts[i].split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                switch (kv[0]) {
                    case "glass" -> glass = !kv[1].equals("0");
                    case "view" -> viewRotate = kv[1].equals("1");
                    case "rot" -> {
                        String[] rot = kv[1].split("x");
                        if (rot.length == 2) {
                            yaw = Integer.parseInt(rot[0]);
                            pitch = Integer.parseInt(rot[1]);
                        }
                    }
                    default -> options.put(kv[0], kv[1]);
                }
            }
            return new MultiblockSpec(type, w, h, d, glass, viewRotate, yaw, pitch, options).normalized();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
