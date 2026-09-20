package net.gabriele333.gabrielequests.client.structure.gaia;

import net.gabriele333.gabrielequests.client.structure.TemplateAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.Random;

/**
 * The Gaia Dimension's Malachite Watchtower: the foyer, a first floor, four or five storeys
 * drawn at random, and a roof.
 *
 * <p>{@code MalachiteWatchtowerPieces#buildStructure} stacks the tower by accumulating a height
 * as it goes - 14 for the foyer, then 10 per storey - and shifting the narrower pieces sideways
 * so they sit centred on the 31-wide foyer ({@code offsetBig}, and {@code offsetSmall} for the
 * roof, which is as wide as the foyer again). Every second storey is the mirrored set
 * ({@code *_m}), which is what keeps the tower's staircase turning back on itself instead of
 * spiralling one way forever; the roof follows the same alternation, so which of the two roof
 * templates ends the tower depends on how many storeys were rolled.</p>
 *
 * <p>The offsets are read at {@link Rotation#NONE}, the rotation a display fixes. The mod
 * chooses one of the four at random and keeps a matching offset for each, precisely because the
 * pieces are not square about the tower's origin - so taking the unrotated pair is taking one
 * of the four towers the generator builds, not an approximation of them.</p>
 *
 * <p><b>What a display leaves out.</b> The {@code MalachiteDegradeProcessor} that weathers one
 * block in five as the pieces are placed, and the data markers the mod turns into loot crates
 * and the Malachite Guard's spawner - both are decided per block at placement time, with a
 * world to place into. The tower's shape and its floor plan are all here.</p>
 */
final class MalachiteWatchtowerBuilder {

    private static final String FOYER = "foyer";
    private static final String[] FIRST_FLOORS = {"floor1_1", "floor1_2", "floor1_3"};
    private static final String[] FLOORS =
            {"floor_random1", "floor_random2", "floor_random3", "floor_random4", "floor_random5"};
    private static final String[] FLOORS_MIRRORED =
            {"floor_random1_m", "floor_random2_m", "floor_random3_m", "floor_random4_m", "floor_random5_m"};
    private static final String ROOF = "roof";
    private static final String ROOF_MIRRORED = "roof_m";

    /** The mod's {@code offsetNoneBig} / {@code offsetNoneSmall}: where a piece sits at {@link Rotation#NONE}. */
    private static final BlockPos FLOOR_OFFSET = new BlockPos(5, 0, 6);
    private static final BlockPos ROOF_OFFSET = new BlockPos(0, 0, 1);

    private MalachiteWatchtowerBuilder() {
    }

    static void build(TemplateAssembly assembly) {
        Random random = assembly.random();
        int y = 0;
        if (!place(assembly, FOYER, BlockPos.ZERO, y)) {
            return; // the Gaia Dimension is not installed
        }
        y += 14;
        place(assembly, FIRST_FLOORS[random.nextInt(FIRST_FLOORS.length)], FLOOR_OFFSET, y);

        int storeys = random.nextInt(2) + 4;
        for (int storey = 0; storey < storeys; storey++) {
            y += 10;
            // The alternation runs the other way for the roof than for the floors, and that is
            // the mod's, not a slip here: an even storey takes a plain floor but the mirrored
            // roof. Which makes sense from inside - the roof caps the storey below it, so it
            // has to face the way that storey's staircase left off.
            boolean even = storey % 2 == 0;
            if (storey == storeys - 1) {
                place(assembly, even ? ROOF_MIRRORED : ROOF, ROOF_OFFSET, y);
            } else {
                String[] set = even ? FLOORS : FLOORS_MIRRORED;
                place(assembly, set[random.nextInt(set.length)], FLOOR_OFFSET, y);
            }
        }
    }

    private static boolean place(TemplateAssembly assembly, String piece, BlockPos offset, int height) {
        return assembly.place(GaiaStructures.NAMESPACE + ":watchtower/" + piece,
                offset.above(height), Rotation.NONE);
    }
}
