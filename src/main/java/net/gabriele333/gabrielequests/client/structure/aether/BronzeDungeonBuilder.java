package net.gabriele333.gabrielequests.client.structure.aether;

import net.gabriele333.gabrielequests.client.structure.StructureDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Aether's Bronze Dungeon, laid out the way its own {@code BronzeDungeonBuilder} lays it
 * out: a directed graph of rooms grown outward from the boss room.
 *
 * <p>The boss room is the root. A corridor and a chest room hang off it, and from there each
 * pass tries the three directions that are not "back the way we came" in a random order,
 * placing a corridor plus a room wherever nothing is already standing - recursing through
 * existing rooms when a direction is already taken, so the dungeon grows at its edge rather
 * than only next to the boss. The last room placed becomes the lobby, and an entrance plus a
 * run of corridors leads out of it. Room count comes from the structure JSON's
 * {@code maxrooms}.</p>
 *
 * <p><b>What a world gives it that a quest book cannot.</b> The real builder asks the chunk
 * generator two questions this has no way to ask: whether a room would be fully buried
 * ({@code isCoveredAtPos}, four noise columns) and whether the exit tunnel has broken out into
 * open air. Both are answered optimistically here - every spot is buildable, and the tunnel
 * never finds daylight - so the shape is the dungeon this builder would produce in generous
 * terrain. The one place that needs a real limit is the exit: with no surface to reach, the
 * mod's loop would run it out to a hundred blocks, so it stops after
 * {@value #MAX_EXIT_SEGMENTS} segments, which reads as a tunnel leaving the lobby without
 * dominating the picture. The surface ruins that cap it in the world are dropped for the same
 * reason - they are placed against the terrain height, and there is no terrain.</p>
 *
 * <p>Block processors are not run either (the sentry-stone and mossy-holystone speckle the mod
 * sprinkles at generation time), so the walls are the plain templates.</p>
 */
final class BronzeDungeonBuilder {

    private static final String BOSS_ROOM = "boss_room";
    private static final String CHEST_ROOM = "chest_room";
    private static final String LOBBY = "lobby";
    private static final String TUNNEL = "square_tunnel";
    private static final String ENTRANCE = "entrance";
    private static final String CORRIDOR = "end_corridor";

    /** Rooms to grow when the structure JSON does not say. */
    private static final int DEFAULT_MAX_ROOMS = 8;
    /** How far the exit tunnel may run without a surface to stop it. */
    private static final int MAX_EXIT_SEGMENTS = 12;
    /** The mod's own sprawl limit: a room must stay within three chunks of the start. */
    private static final int MAX_CHUNK_DISTANCE = 3;

    /** One placed template. Compared by identity, exactly as the mod compares its pieces. */
    private static final class Piece {
        final String name;
        final BlockPos pos;
        final Rotation rotation;
        final BoundingBox box;
        final boolean boss;

        Piece(String name, BlockPos pos, Rotation rotation, BoundingBox box, boolean boss) {
            this.name = name;
            this.pos = pos;
            this.rotation = rotation;
            this.box = box;
            this.boss = boss;
        }
    }

    private final AetherAssembly assembly;
    private final Random random;
    private final int nodeWidth;
    private final int edgeWidth;
    private final int maxSize;

    /** Rooms, in placement order - the last one is the lobby, as in the mod. */
    private final List<Piece> nodes = new ArrayList<>();
    /** The corridors joining them; placed after the rooms, before the boss room. */
    private final List<Piece> hallways = new ArrayList<>();
    /** Which room a given room already connects to, per direction. */
    private final Map<Piece, Map<Direction, Piece>> edges = new IdentityHashMap<>();

    private BronzeDungeonBuilder(AetherAssembly assembly, int nodeWidth, int edgeWidth, int maxSize) {
        this.assembly = assembly;
        this.random = assembly.random();
        this.nodeWidth = nodeWidth;
        this.edgeWidth = edgeWidth;
        this.maxSize = maxSize;
    }

    static void build(AetherAssembly assembly, StructureDefinition definition) {
        BlockPos nodeSize = assembly.size(CHEST_ROOM);
        BlockPos edgeSize = assembly.size(TUNNEL);
        if (nodeSize == null || edgeSize == null) {
            return; // the Aether is not installed
        }
        new BronzeDungeonBuilder(assembly, nodeSize.getX(), edgeSize.getX(),
                Math.max(3, definition.number("maxrooms", DEFAULT_MAX_ROOMS))).assemble();
    }

    private void assemble() {
        // The structure's own rotation is fixed: a display is one picture, and a random
        // quarter turn of the whole dungeon would only change which way it faces.
        Rotation rotation = Rotation.NONE;
        Direction direction = rotation.rotate(Direction.SOUTH);

        Piece bossRoom = piece(BOSS_ROOM, BlockPos.ZERO, rotation, true);
        if (bossRoom == null) {
            return;
        }
        // The corridor leaves the boss room two blocks up, at the height of its doorway.
        Piece hallway = piece(TUNNEL,
                AetherGeometry.tunnelFromEvenRoom(bossRoom.box.moved(0, 2, 0), direction, edgeWidth),
                rotation, false);
        if (hallway == null) {
            return;
        }
        Piece first = piece(CHEST_ROOM,
                AetherGeometry.tunnelFromEvenRoom(hallway.box, direction, nodeWidth), rotation, false);
        if (first == null) {
            return;
        }
        nodes.add(bossRoom);
        nodes.add(first);
        connect(bossRoom, first, hallway, direction);

        for (int i = 2; i < maxSize - 1; i++) {
            propagate(first, false);
        }
        propagate(first, true);
        buildExitTunnel(nodes.get(nodes.size() - 1));

        // Placement order matters: the boss room goes down last so the exit tunnel cannot
        // carve through its doorway blocks, which is what the mod does and why.
        for (int i = 1; i < nodes.size(); i++) {
            place(nodes.get(i));
        }
        hallways.forEach(this::place);
        place(bossRoom);
    }

    /**
     * Tries to hang one more room off the graph, walking through rooms that are already
     * connected in the chosen direction. Returns true once a room has been placed.
     */
    private boolean propagate(Piece current, boolean placeLobby) {
        List<Rotation> rotations = new ArrayList<>(3);
        rotations.add(current.rotation.getRotated(Rotation.COUNTERCLOCKWISE_90));
        rotations.add(current.rotation);
        rotations.add(current.rotation.getRotated(Rotation.CLOCKWISE_90));
        String roomName = placeLobby ? LOBBY : CHEST_ROOM;

        for (int i = 3; i > 0; i--) {
            Rotation rotation = rotations.remove(random.nextInt(i));
            Direction direction = rotation.rotate(Direction.SOUTH);
            Piece existing = connection(current, direction);
            if (existing != null) {
                if (propagate(existing, placeLobby)) {
                    return true;
                }
                continue;
            }
            Piece hallway = piece(TUNNEL,
                    AetherGeometry.tunnelFromEvenRoom(current.box, direction, edgeWidth), rotation, false);
            if (hallway == null) {
                continue;
            }
            Piece room = piece(roomName,
                    AetherGeometry.tunnelFromEvenRoom(hallway.box, direction, nodeWidth), rotation, false);
            if (room == null || !closeToCenter(room.pos)) {
                continue;
            }
            Piece collision = collision(room.box);
            if (collision == null) {
                nodes.add(room);
                connect(current, room, hallway, direction);
                return true;
            }
            // Something is already there. If it is not the boss room and the two are not
            // joined yet, join them anyway - that is what turns a tree of rooms into a
            // dungeon you can walk a loop through - and keep looking for a free direction.
            if (!collision.boss && !connectsTo(collision, current)) {
                connect(current, room, hallway, direction);
            }
        }
        return false;
    }

    /**
     * The way out: an entrance doorway in the lobby wall, then corridors marching outward.
     * Three directions are tried and the longest run wins, as in the mod - which then keeps
     * going until it breaks the surface, where this stops at a fixed length instead.
     */
    private void buildExitTunnel(Piece lobby) {
        BlockPos entranceSize = assembly.size(ENTRANCE);
        if (entranceSize == null) {
            return;
        }
        List<Rotation> rotations = new ArrayList<>(3);
        rotations.add(lobby.rotation.getRotated(Rotation.COUNTERCLOCKWISE_90));
        rotations.add(lobby.rotation);
        rotations.add(lobby.rotation.getRotated(Rotation.CLOCKWISE_90));

        List<Piece> longest = List.of();
        for (int i = 3; i > 0; i--) {
            Rotation rotation = rotations.remove(random.nextInt(i));
            Direction direction = rotation.rotate(Direction.SOUTH);
            List<Piece> tunnel = new ArrayList<>();
            BlockPos start = AetherGeometry.tunnelFromEvenRoom(lobby.box, direction, entranceSize.getX());
            Piece entrance = piece(ENTRANCE, start, rotation, false);
            if (entrance == null) {
                return;
            }
            tunnel.add(entrance);
            start = start.relative(direction);
            // The mod steps by the entrance's depth, not the corridor's, so the corridors
            // overlap into one continuous passage. Kept as it is: stepping by the corridor
            // length instead would leave gaps between them.
            int step = Math.max(1, entranceSize.getZ());
            for (int segment = 0; segment < MAX_EXIT_SEGMENTS; segment++) {
                Piece corridor = piece(CORRIDOR, start.relative(direction, segment * step), rotation, false);
                if (corridor == null || collidesWithRoom(corridor.box, lobby)) {
                    break;
                }
                tunnel.add(corridor);
            }
            if (tunnel.size() > longest.size()) {
                longest = tunnel;
            }
        }
        nodes.addAll(longest);
    }

    @Nullable
    private Piece piece(String name, BlockPos pos, Rotation rotation, boolean boss) {
        BoundingBox box = assembly.box(name, pos, rotation);
        return box == null ? null : new Piece(name, pos, rotation, box, boss);
    }

    private void place(Piece piece) {
        assembly.place(piece.name, piece.pos, piece.rotation);
    }

    private void connect(Piece from, Piece to, Piece hallway, Direction direction) {
        edges.computeIfAbsent(from, key -> new IdentityHashMap<>()).put(direction, to);
        hallways.add(hallway);
    }

    @Nullable
    private Piece connection(Piece from, Direction direction) {
        Map<Direction, Piece> map = edges.get(from);
        return map == null ? null : map.get(direction);
    }

    private boolean connectsTo(Piece from, Piece to) {
        Map<Direction, Piece> map = edges.get(from);
        return map != null && map.containsValue(to);
    }

    @Nullable
    private Piece collision(BoundingBox box) {
        for (Piece node : nodes) {
            if (node.box.intersects(box)) {
                return node;
            }
        }
        return null;
    }

    /** As above, but the room the tunnel is digging out of does not count as in the way. */
    private boolean collidesWithRoom(BoundingBox box, Piece from) {
        for (Piece node : nodes) {
            if (node != from && node.box.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** The mod's sprawl limit, measured in chunks from the start position at the origin. */
    private static boolean closeToCenter(BlockPos pos) {
        return Math.max(Math.abs(pos.getX() >> 4), Math.abs(pos.getZ() >> 4)) <= MAX_CHUNK_DISTANCE;
    }
}
