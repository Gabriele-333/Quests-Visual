package net.gabriele333.gabrielequests.client.portal;

import org.jetbrains.annotations.Nullable;

/**
 * The serialisable description of a displayed portal: which portal block, which frame block,
 * the shape they are arranged in, the interior size, the look flags and the persisted orbit
 * angles. This is the whole persisted state; the block layout is derived from it by
 * {@link PortalShape} and the rendered texture is cached.
 *
 * <p>The string form is
 * {@code gabrielequests-portal:id=<block>,frame=<block>,shape=<arch|flat|gateway>,size=<w>x<h>x<d>,axis=<x|z>,corners=<0|1>,lit=<0|1>,base=<0|1>,decor=<0|1>,eyes=<0|1>,view=<0|1>,rot=<yaw>x<pitch>}.
 * Like the other display specs it has to survive FTB Library's icon-string handling, so it
 * contains neither {@code ";"} (property separator in {@code Icon.getIcon}) nor {@code " + "}
 * (combined-icon separator); block ids never contain {@code ","}, {@code "="} or spaces, so
 * the field separators are safe. Unknown keys are dropped on parse, so a string written by a
 * newer version still loads.</p>
 *
 * <p>The two block ids are carried in the spec rather than derived from a portal catalogue at
 * render time on purpose: it makes a display portal <b>self-contained</b> (an author can
 * combine any portal with any frame - a quartz nether portal, a deepslate end portal - and it
 * renders the same for everyone), and it means a portal from a mod this code has never heard
 * of round-trips exactly. {@link PortalCatalog} only supplies the <em>defaults</em>, when a
 * portal is first picked.</p>
 *
 * <p>This record is deliberately common-safe (no client classes, no registry lookups in
 * {@link #normalized()}): only {@code String}, {@code int}, {@code boolean} and the shape
 * enum, so a dedicated server can read and re-serialise it. {@link #parse} never throws; on
 * malformed input the caller keeps the raw string untouched.</p>
 */
public record PortalSpec(String portalId, String frameId, PortalShape shape,
                         int width, int height, int depth, String axis,
                         boolean corners, boolean lit, boolean base, boolean decor, boolean eyes,
                         boolean viewRotate, int yaw, int pitch) {

    public static final String PREFIX = "gabrielequests-portal:";
    /** The classic isometric view, same as the display multiblock and structure. */
    public static final int DEFAULT_YAW = 225;
    public static final int DEFAULT_PITCH = 30;
    /** Vanilla, so it exists in every install and a fresh display always renders. */
    public static final String DEFAULT_PORTAL = "minecraft:nether_portal";
    public static final String DEFAULT_FRAME = "minecraft:obsidian";

    /** The minimal vanilla nether portal, seen nearly face-on, no view-rotate. */
    public static PortalSpec defaultSpec() {
        return new PortalSpec(DEFAULT_PORTAL, DEFAULT_FRAME, PortalShape.ARCH, 2, 3, 1, "x",
                true, true, false, false, true, false,
                PortalShape.ARCH.defaultYaw(), PortalShape.ARCH.defaultPitch()).normalized();
    }

    /** Sanitises the ids and the axis, clamps the size per shape and wraps the view angles. */
    public PortalSpec normalized() {
        PortalSpec clamped = shape.clamp(this);
        return new PortalSpec(clean(portalId, DEFAULT_PORTAL), clean(frameId, DEFAULT_FRAME), shape,
                Math.clamp(clamped.width(), 1, 21), Math.clamp(clamped.height(), 1, 21),
                Math.clamp(clamped.depth(), 1, 21), "z".equals(axis) ? "z" : "x",
                corners, lit, base, decor, eyes,
                viewRotate, Math.floorMod(yaw, 360), Math.clamp(pitch, -89, 89));
    }

    /** Drops nulls and anything that would break the serialisation separators. */
    private static String clean(@Nullable String id, String fallback) {
        if (id == null || id.isEmpty() || id.contains(",") || id.contains("=") || id.contains(";")
                || id.contains(" ") || id.contains("+")) {
            return fallback;
        }
        return id;
    }

    public boolean zAxis() {
        return "z".equals(axis);
    }

    /**
     * A raw copy with a new size, for {@link PortalShape#clamp} - deliberately <b>not</b>
     * re-normalised, since normalisation is what calls it.
     */
    PortalSpec withClampedSize(int w, int h, int d) {
        return new PortalSpec(portalId, frameId, shape, w, h, d, axis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch);
    }

    /**
     * Switches to another portal block <b>with that portal's defaults</b> (frame, shape,
     * size, flags), keeping only the view. This is the one setter that cascades, so picking
     * "End portal" after "Nether portal" gives an end-portal ring rather than an obsidian
     * arch with an end-portal surface in it.
     */
    public PortalSpec withPortal(PortalSpec defaults) {
        return new PortalSpec(defaults.portalId(), defaults.frameId(), defaults.shape(),
                defaults.width(), defaults.height(), defaults.depth(), defaults.axis(),
                defaults.corners(), defaults.lit(), defaults.base(), defaults.decor(),
                defaults.eyes(), viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withFrame(String id) {
        return new PortalSpec(portalId, id, shape, width, height, depth, axis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withShape(PortalShape newShape) {
        return new PortalSpec(portalId, frameId, newShape, width, height, depth, axis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withWidth(int w) {
        return new PortalSpec(portalId, frameId, shape, w, height, depth, axis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withHeight(int h) {
        return new PortalSpec(portalId, frameId, shape, width, h, depth, axis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withDepth(int d) {
        return new PortalSpec(portalId, frameId, shape, width, height, d, axis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withAxis(String newAxis) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, newAxis,
                corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withCorners(boolean c) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                c, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withLit(boolean l) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                corners, l, base, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withBase(boolean b) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                corners, lit, b, decor, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withDecor(boolean d) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                corners, lit, base, d, eyes, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withEyes(boolean e) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                corners, lit, base, decor, e, viewRotate, yaw, pitch).normalized();
    }

    public PortalSpec withViewRotate(boolean v) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                corners, lit, base, decor, eyes, v, yaw, pitch).normalized();
    }

    public PortalSpec withRotation(int newYaw, int newPitch) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                corners, lit, base, decor, eyes, viewRotate, newYaw, newPitch).normalized();
    }

    public String serialize() {
        return PREFIX + "id=" + portalId + ",frame=" + frameId + ",shape=" + shape.id()
                + ",size=" + width + "x" + height + "x" + depth
                + ",axis=" + axis
                + ",corners=" + (corners ? 1 : 0)
                + ",lit=" + (lit ? 1 : 0)
                + ",base=" + (base ? 1 : 0)
                + ",decor=" + (decor ? 1 : 0)
                + ",eyes=" + (eyes ? 1 : 0)
                + ",view=" + (viewRotate ? 1 : 0)
                + ",rot=" + yaw + "x" + pitch;
    }

    /** Parses and normalises a serialised spec; {@code null} if not ours or malformed. */
    @Nullable
    public static PortalSpec parse(String s) {
        if (s == null || !s.startsWith(PREFIX)) {
            return null;
        }
        try {
            PortalSpec spec = defaultSpec();
            String portalId = spec.portalId();
            String frameId = spec.frameId();
            PortalShape shape = spec.shape();
            int width = spec.width();
            int height = spec.height();
            int depth = spec.depth();
            String axis = spec.axis();
            boolean corners = spec.corners();
            boolean lit = spec.lit();
            boolean base = spec.base();
            boolean decor = spec.decor();
            boolean eyes = spec.eyes();
            boolean viewRotate = false;
            int yaw = spec.yaw();
            int pitch = spec.pitch();
            for (String part : s.substring(PREFIX.length()).split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                switch (kv[0]) {
                    case "id" -> portalId = kv[1];
                    case "frame" -> frameId = kv[1];
                    case "shape" -> {
                        PortalShape parsed = PortalShape.byId(kv[1]);
                        if (parsed != null) {
                            shape = parsed;
                        }
                    }
                    case "size" -> {
                        String[] dims = kv[1].split("x");
                        if (dims.length == 3) {
                            width = Integer.parseInt(dims[0]);
                            height = Integer.parseInt(dims[1]);
                            depth = Integer.parseInt(dims[2]);
                        }
                    }
                    case "axis" -> axis = kv[1];
                    case "corners" -> corners = kv[1].equals("1");
                    case "lit" -> lit = kv[1].equals("1");
                    case "base" -> base = kv[1].equals("1");
                    case "decor" -> decor = kv[1].equals("1");
                    case "eyes" -> eyes = kv[1].equals("1");
                    case "view" -> viewRotate = kv[1].equals("1");
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
            return new PortalSpec(portalId, frameId, shape, width, height, depth, axis,
                    corners, lit, base, decor, eyes, viewRotate, yaw, pitch).normalized();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
