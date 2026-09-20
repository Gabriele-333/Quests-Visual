package net.gabriele333.gabrielequests.client.portal;

import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three shapes a display portal can take, i.e. how a portal block and a frame block are
 * laid out in space. Everything the shapes need comes from the {@link PortalSpec} (both block
 * ids, the interior size and the flags), so one shape serves every portal that is built that
 * way - which is what lets the feature cover mod portals it has never heard of.
 *
 * <ul>
 *   <li>{@link #ARCH} - the nether-portal family: an upright rectangular frame with the
 *       portal surface inside it. Interior 2..21 wide and 3..21 tall (vanilla's
 *       {@code PortalShape} limits, which most mods copy), one block thin, along the X or the
 *       Z axis; the four corners are optional because vanilla does not require them.</li>
 *   <li>{@link #FLAT} - the end-portal family: a horizontal ring of frame blocks around a
 *       flat portal surface. Optionally on a solid base and with plants on the rim, which is
 *       what the Twilight Forest portal (a pool in the ground, ringed by flowers) needs.</li>
 *   <li>{@link #GATEWAY} - the end gateway's bedrock cage, reproduced from
 *       {@code EndGatewayFeature}: a plus of frame blocks one block above and below the
 *       portal block plus a single cap two blocks out, in a 3&#215;5&#215;3 box.</li>
 * </ul>
 *
 * <p>Blockstate properties are set <b>by name</b> ({@link BlockStates#with}), never through a
 * class: {@code axis} on the portal surface, {@code facing} and {@code eye} on the frame. So
 * the vanilla end portal comes out with its frames oriented exactly as the stronghold builds
 * them ({@code StrongholdPieces.PortalRoom}: north/south on the Z sides, pointing the other
 * way on the X sides - vanilla's own asymmetry, also encoded in
 * {@code EndPortalFrameBlock.getOrCreatePortalShape}), and a mod frame block that happens to
 * have the same properties gets the same treatment for free.</p>
 */
public enum PortalShape {

    ARCH("arch") {
        @Override
        public PortalSpec clamp(PortalSpec spec) {
            // Vanilla's own limits (PortalShape.MIN/MAX_WIDTH, MIN/MAX_HEIGHT). The depth is
            // left untouched rather than forced to 1: a shape only clamps what it uses, so
            // switching shapes back and forth does not quietly destroy the other one's size.
            return spec.withClampedSize(Math.clamp(spec.width(), 2, 21),
                    Math.clamp(spec.height(), 3, 21), spec.depth());
        }

        @Override
        public BlockPos size(PortalSpec spec) {
            int across = spec.width() + 2;
            int tall = spec.height() + 2;
            // The frame is one block thin; which axis it spans is the "axis" option.
            return spec.zAxis() ? new BlockPos(1, tall, across) : new BlockPos(across, tall, 1);
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(PortalSpec spec, BlockState frame,
                                                     @Nullable BlockState portal) {
            BlockState surface = BlockStates.with(portal, "axis", spec.axis());
            int across = spec.width() + 2;
            int tall = spec.height() + 2;
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int a = 0; a < across; a++) {
                for (int y = 0; y < tall; y++) {
                    boolean sideA = a == 0 || a == across - 1;
                    boolean sideY = y == 0 || y == tall - 1;
                    BlockPos pos = spec.zAxis() ? new BlockPos(0, y, a) : new BlockPos(a, y, 0);
                    if (sideA || sideY) {
                        if (!(sideA && sideY) || spec.corners()) {
                            map.put(pos, frame);
                        }
                    } else if (surface != null) {
                        map.put(pos, surface);
                    }
                }
            }
            return map;
        }
    },

    FLAT("flat") {
        @Override
        public PortalSpec clamp(PortalSpec spec) {
            // Height is not used (the ring is one layer, plus the optional base and plants) and
            // therefore not touched - see the note in ARCH.
            return spec.withClampedSize(Math.clamp(spec.width(), 1, 21), spec.height(),
                    Math.clamp(spec.depth(), 1, 21));
        }

        @Override
        public BlockPos size(PortalSpec spec) {
            return new BlockPos(spec.width() + 2,
                    1 + (spec.base() ? 1 : 0) + (spec.decor() ? 1 : 0),
                    spec.depth() + 2);
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(PortalSpec spec, BlockState frame,
                                                     @Nullable BlockState portal) {
            int wide = spec.width() + 2;
            int deep = spec.depth() + 2;
            int level = spec.base() ? 1 : 0;
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < wide; x++) {
                for (int z = 0; z < deep; z++) {
                    if (spec.base()) {
                        map.put(new BlockPos(x, 0, z), frame);
                    }
                    boolean sideX = x == 0 || x == wide - 1;
                    boolean sideZ = z == 0 || z == deep - 1;
                    if (sideX || sideZ) {
                        if ((sideX && sideZ) && !spec.corners()) {
                            continue;
                        }
                        map.put(new BlockPos(x, level, z), rim(spec, frame, x, z, wide, deep));
                        if (spec.decor()) {
                            BlockState plant = BlockStates.state(plantFor(x, z));
                            if (plant != null) {
                                map.put(new BlockPos(x, level + 1, z), plant);
                            }
                        }
                    } else if (portal != null) {
                        map.put(new BlockPos(x, level, z), portal);
                    }
                }
            }
            return map;
        }

        /** A ring block, pointed and socketed the way the vanilla end portal's frames are. */
        private BlockState rim(PortalSpec spec, BlockState frame, int x, int z, int wide, int deep) {
            BlockState state = BlockStates.with(frame, "eye", String.valueOf(spec.eyes()));
            if (!BlockStates.has(state, "facing")) {
                return state;
            }
            String facing;
            if (z == 0) {
                facing = "north";
            } else if (z == deep - 1) {
                facing = "south";
            } else if (x == 0) {
                facing = "east";
            } else {
                facing = "west";
            }
            return BlockStates.with(state, "facing", facing);
        }
    },

    GATEWAY("gateway") {
        @Override
        public PortalSpec clamp(PortalSpec spec) {
            // The cage is a fixed shape; the size rows are simply not used by it, and are
            // left alone so switching back to another shape restores them.
            return spec;
        }

        @Override
        public BlockPos size(PortalSpec spec) {
            return new BlockPos(3, 5, 3);
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(PortalSpec spec, BlockState frame,
                                                     @Nullable BlockState portal) {
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 5; y++) {
                    for (int z = 0; z < 3; z++) {
                        boolean middleX = x == 1;
                        boolean middleY = y == 2;
                        boolean middleZ = z == 1;
                        boolean cap = Math.abs(y - 2) == 2;
                        if (middleX && middleY && middleZ) {
                            if (portal != null) {
                                map.put(new BlockPos(x, y, z), portal);
                            }
                        } else if (middleY) {
                            continue; // the portal's own layer is open on all sides
                        } else if (cap ? middleX && middleZ : middleX || middleZ) {
                            map.put(new BlockPos(x, y, z), frame);
                        }
                    }
                }
            }
            return map;
        }
    };

    /**
     * Plants for the rim of a flat portal, all from the Twilight Forest's
     * {@code portal/generated_decoration} tag (vanilla entries only, so they exist in every
     * install). Picked by position, not randomly, so a display renders the same for everyone
     * and re-renders identically.
     */
    private static final List<String> PLANTS = List.of(
            "minecraft:short_grass", "minecraft:poppy", "minecraft:fern", "minecraft:oxeye_daisy",
            "minecraft:red_mushroom", "minecraft:allium", "minecraft:blue_orchid",
            "minecraft:brown_mushroom", "minecraft:cornflower", "minecraft:azure_bluet",
            "minecraft:red_tulip", "minecraft:lily_of_the_valley");

    private static String plantFor(int x, int z) {
        return PLANTS.get(Math.floorMod(x * 7 + z * 3, PLANTS.size()));
    }

    private final String id;

    PortalShape(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * The view a fresh display of this shape opens on. The flat and cage shapes want the
     * classic isometric angle of every other display; an upright frame does not - it is one
     * block thin, so at 45&#176; it reads as a diagonal sliver, while nearly face-on (with just
     * enough yaw/pitch to keep it three-dimensional) is how a portal is recognisable.
     */
    public int defaultYaw() {
        return this == ARCH ? 200 : PortalSpec.DEFAULT_YAW;
    }

    public int defaultPitch() {
        return this == ARCH ? 12 : PortalSpec.DEFAULT_PITCH;
    }

    public String translationKey() {
        return "gabrielequests.portal.shape." + id;
    }

    /** Normalises a spec into the size range this shape uses (validation, done once). */
    public abstract PortalSpec clamp(PortalSpec spec);

    /** Bounding-box size in blocks; generated positions run 0..size-1 on each axis. */
    public abstract BlockPos size(PortalSpec spec);

    /**
     * The blocks of a display portal, or {@code null} when either block id is not registered
     * (the portal's mod is not installed) - callers then draw a placeholder. The portal
     * surface is omitted when the spec is not lit, which leaves exactly the frame structure.
     */
    @Nullable
    public Map<BlockPos, BlockState> generate(PortalSpec spec) {
        PortalSpec normalized = spec.normalized();
        BlockState frame = BlockStates.state(normalized.frameId());
        BlockState portal = BlockStates.state(normalized.portalId());
        if (frame == null || portal == null) {
            return null;
        }
        return generateNormalized(normalized, frame, normalized.lit() ? portal : null);
    }

    @Nullable
    abstract Map<BlockPos, BlockState> generateNormalized(PortalSpec spec, BlockState frame,
                                                          @Nullable BlockState portal);

    @Nullable
    public static PortalShape byId(String id) {
        for (PortalShape shape : values()) {
            if (shape.id.equals(id)) {
                return shape;
            }
        }
        return null;
    }
}
