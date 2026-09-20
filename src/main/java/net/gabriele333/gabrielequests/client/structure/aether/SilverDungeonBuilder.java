package net.gabriele333.gabrielequests.client.structure.aether;

import net.gabriele333.gabrielequests.client.structure.StructureDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Aether's Silver Dungeon: the floating temple, and the 3x3x3 grid of rooms inside it.
 *
 * <p>Two halves, both ports of the mod's own code. The shell is fixed - the {@code rear}
 * temple with the Valkyrie Queen's throne room tucked inside it, and the {@code skeleton} that
 * holds the rooms, placed one rear-length along - while the rooms themselves come from
 * {@code SilverDungeonBuilder}: three staircases threaded up through the three floors, then a
 * recursive walk that opens a door between rooms wherever it can pass, and a chest room in
 * most of what is left over. Every cell is a floor slab plus its north and west walls, so a
 * "door" is one wall template swapped for another - which is why the maze reads as rooms
 * rather than as corridors.</p>
 *
 * <p>The cloud bed the mod scatters underneath is left out: it is a hundred random aercloud
 * blobs spread over a 50x77 area to work around chunk boundaries during generation, and in a
 * quest-book icon it would be a haze of white four times wider than the dungeon it is meant to
 * hold up. Block processors are not run either, so the walls are the plain templates.</p>
 */
final class SilverDungeonBuilder {

    private static final String REAR = "rear";
    private static final String SKELETON = "skeleton";
    private static final String BOSS_ROOM = "boss_room";
    private static final String FLOOR = "floor";
    private static final String WALL = "wall";
    private static final String DOOR = "door";
    private static final String BOSS_DOOR = "boss_door";
    private static final String STAIRCASE = "staircase";
    private static final String TALL_STAIRCASE = "tall_staircase";
    private static final String CHEST_ROOM = "chest_room";

    /** Room flags, as the mod packs them into one int per cell. */
    private static final int CHEST = 0b1;
    private static final int STAIRS = 0b10;
    private static final int FINAL_STAIRS = 0b100;
    private static final int STAIRS_MIDDLE = 0b1000;
    private static final int STAIRS_TOP = 0b10000;
    private static final int NORTH_DOOR = 0b100000;
    private static final int WEST_DOOR = 0b1000000;
    private static final int VISITED = 0b10000000;
    /** The five "this cell is already something" flags, i.e. everything but the doors. */
    private static final int OCCUPIED = 0b11111;

    /** The grid the mod builds: three rooms each way, seven blocks apart, five tall. */
    private static final int GRID = 3;
    private static final int CELL_WIDTH = 7;
    private static final int CELL_HEIGHT = 5;

    private final AetherAssembly assembly;
    private final Random random;
    private final int[][][] grid = new int[GRID][GRID][GRID];

    private SilverDungeonBuilder(AetherAssembly assembly) {
        this.assembly = assembly;
        this.random = assembly.random();
    }

    static void build(AetherAssembly assembly, StructureDefinition definition) {
        new SilverDungeonBuilder(assembly).assemble();
    }

    private void assemble() {
        // Fixed rotation, so the structure faces south and every offset below is the mod's
        // own with its direction steps resolved.
        Rotation rotation = Rotation.NONE;
        Direction direction = rotation.rotate(Direction.SOUTH);
        int stepX = direction.getStepX();
        int stepZ = direction.getStepZ();

        // Where the mod puts the temple relative to the generation point: 54 back and 15 to
        // the side, so the dungeon hangs off the corner of the chunk it was rolled for.
        BlockPos elevated = BlockPos.ZERO.relative(rotation.rotate(Direction.NORTH), 54)
                .relative(rotation.rotate(Direction.WEST), 15);
        if (!assembly.place(REAR, elevated, rotation)) {
            return; // the Aether is not installed
        }
        BoundingBox rear = assembly.box(REAR, elevated, rotation);
        if (rear == null) {
            return;
        }
        assembly.place(BOSS_ROOM,
                elevated.offset((stepX + stepZ) * 5, 3, (stepZ - stepX) * 5), rotation);

        BlockPos gridOrigin = elevated.offset(stepX * rear.getXSpan(), 0, stepZ * rear.getZSpan());
        assembly.place(SKELETON, gridOrigin, rotation);

        populateGrid();
        placeRooms(gridOrigin, rotation, direction);
    }

    /** Threads the staircases through the floors, then walks the rooms opening doors. */
    private void populateGrid() {
        int finalStairs = random.nextInt(GRID);
        grid[finalStairs][0][0] = FINAL_STAIRS;
        grid[finalStairs][1][0] = STAIRS_MIDDLE;
        grid[finalStairs][2][0] = STAIRS_TOP;

        int firstStairs = random.nextInt(GRID);
        grid[firstStairs][0][1] = STAIRS;
        grid[firstStairs][1][1] = STAIRS_TOP;

        int secondStairs = random.nextInt(GRID);
        grid[secondStairs][1][2] = STAIRS;
        grid[secondStairs][2][2] = STAIRS_TOP;

        for (int y = 0; y < GRID; y++) {
            traverse(1, y, 1, 0);
            for (int z = 0; z < GRID; z++) {
                for (int x = 0; x < GRID; x++) {
                    if ((grid[x][y][z] & OCCUPIED) == 0 && random.nextInt(3) != 0) {
                        grid[x][y][z] |= CHEST;
                    }
                }
            }
        }
    }

    /**
     * The mod's recursive walk. A cell already visited answers "you may connect to me" only
     * one time in three, which is what stops every room from opening into every neighbour;
     * the blacklist keeps a staircase from opening into the one above or below it.
     */
    private boolean traverse(int x, int y, int z, int typesToAvoid) {
        if (x < 0 || x >= GRID || z < 0 || z >= GRID) {
            return false;
        }
        int room = grid[x][y][z];
        if ((room & typesToAvoid) > 0) {
            return false;
        }
        if ((room & VISITED) == VISITED) {
            return random.nextInt(3) == 0;
        }
        grid[x][y][z] |= VISITED;

        int blacklist = FINAL_STAIRS | STAIRS_MIDDLE;
        if ((room & STAIRS_TOP) == STAIRS_TOP) {
            blacklist |= STAIRS;
        }
        if ((room & STAIRS) == STAIRS) {
            blacklist |= STAIRS_TOP;
        }

        List<Direction> directions = new ArrayList<>(List.of(
                Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST));
        for (int i = directions.size(); i > 0; i--) {
            switch (directions.remove(random.nextInt(i))) {
                case NORTH -> {
                    if (traverse(x, y, z - 1, blacklist)) {
                        grid[x][y][z] |= NORTH_DOOR;
                    }
                }
                case SOUTH -> {
                    if (traverse(x, y, z + 1, blacklist)) {
                        grid[x][y][z + 1] |= NORTH_DOOR;
                    }
                }
                case WEST -> {
                    if (traverse(x - 1, y, z, blacklist)) {
                        grid[x][y][z] |= WEST_DOOR;
                    }
                }
                default -> {
                    if (traverse(x + 1, y, z, blacklist)) {
                        grid[x + 1][y][z] |= WEST_DOOR;
                    }
                }
            }
        }
        return true;
    }

    /** Lays the grid out from the top floor down, exactly as {@code assembleDungeon} does. */
    private void placeRooms(BlockPos origin, Rotation rotation, Direction direction) {
        int stepX = direction.getStepX();
        int stepZ = direction.getStepZ();
        BlockPos start = origin.offset(stepZ * 5 - stepX, CELL_HEIGHT, -stepX * 5 - stepZ);
        Rotation sideways = rotation.getRotated(Rotation.CLOCKWISE_90);

        for (int y = GRID - 1; y >= 0; y--) {
            int cellY = start.getY() + y * CELL_HEIGHT;
            for (int z = 0; z < GRID; z++) {
                for (int x = 0; x < GRID; x++) {
                    BlockPos cell = new BlockPos(
                            start.getX() + stepZ * x * CELL_WIDTH + stepX * z * CELL_WIDTH,
                            cellY,
                            start.getZ() + stepZ * z * CELL_WIDTH - stepX * x * CELL_WIDTH);
                    int room = grid[x][y][z];

                    assembly.place(FLOOR, cell.offset(stepX + stepZ, -1, stepZ - stepX), rotation);
                    assembly.place((room & NORTH_DOOR) == NORTH_DOOR ? DOOR : WALL,
                            cell.offset(stepZ, 0, -stepX), rotation);
                    assembly.place((room & WEST_DOOR) == WEST_DOOR ? DOOR : WALL,
                            cell.relative(direction), sideways);

                    if ((room & FINAL_STAIRS) == FINAL_STAIRS) {
                        assembly.place(TALL_STAIRCASE, cell.offset(2, 0, 2), rotation);
                        assembly.place(BOSS_DOOR, cell.offset(stepZ * 3, 0, -stepX * 3), rotation);
                    } else if ((room & STAIRS) == STAIRS) {
                        assembly.place(STAIRCASE, cell.offset(2, 0, 2), rotation);
                    } else if ((room & CHEST) == CHEST) {
                        assembly.place(CHEST_ROOM, cell.offset(3, 0, 3), rotation);
                    }
                }
            }
        }
    }
}
