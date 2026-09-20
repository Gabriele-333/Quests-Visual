package net.gabriele333.gabrielequests.client.structure.gaia;

import net.gabriele333.gabrielequests.client.structure.TemplateAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

/**
 * The Gaia Dimension's mini tower: four templates stacked, in one of four gemstone materials.
 *
 * <p>{@code MiniTowerPieces#buildStructure} is a straight column - base, first floor, second
 * floor, roof - with each piece's vertical offset accumulated as the mod walks the list (0, 8,
 * 19, 28) and a fixed horizontal nudge per part, one block in on both axes for the two narrower
 * floors so they sit centred on the 16-wide base. The material is a single {@code nextInt(4)}
 * that picks one of the four parallel sets of templates.</p>
 *
 * <p><b>What a display leaves out.</b> Two things, both of which need a world. The mod's
 * {@code postProcess} looks up the terrain height under each piece and drops that piece to it,
 * so on a slope the tower's parts settle at slightly different levels; with no terrain there is
 * nothing to settle onto and every piece keeps the offset the generator gave it, which is the
 * tower as it is drawn on flat ground. And the {@code BlockDegradeProcessor} that swaps a
 * fraction of the bricks, stairs and slabs for their cracked and crusted variants is a
 * per-block dice roll at placement time; the display shows the tower intact. Neither changes
 * the shape.</p>
 */
final class MiniTowerBuilder {

    /** The four material sets, in the order {@code MiniTowerType} declares them. */
    private static final String[] MATERIALS = {"amethyst", "copal", "jade", "jet"};

    /** Horizontal nudge per part: the mod's {@code OFFSETS} map, which has only two values. */
    private static final BlockPos WIDE_OFFSET = new BlockPos(0, 1, 0);
    private static final BlockPos NARROW_OFFSET = new BlockPos(1, 0, 1);

    private MiniTowerBuilder() {
    }

    static void build(TemplateAssembly assembly) {
        String material = MATERIALS[assembly.random().nextInt(MATERIALS.length)];
        int y = 0;
        place(assembly, material, "base", WIDE_OFFSET, y);
        y += 8;
        place(assembly, material, "floor_1", NARROW_OFFSET, y);
        y += 11;
        place(assembly, material, "floor_2", NARROW_OFFSET, y);
        y += 9;
        place(assembly, material, "roof", WIDE_OFFSET, y);
    }

    private static void place(TemplateAssembly assembly, String material, String part,
                              BlockPos offset, int height) {
        assembly.place(GaiaStructures.NAMESPACE + ":minitower/" + material + "/" + part,
                offset.above(height), Rotation.NONE);
    }
}
