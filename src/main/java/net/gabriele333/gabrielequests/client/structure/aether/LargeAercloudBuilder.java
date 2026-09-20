package net.gabriele333.gabrielequests.client.structure.aether;

import net.gabriele333.gabrielequests.client.structure.StructureDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * The Aether's large aercloud: the drifting bank of cloud blocks, not a building at all.
 *
 * <p>It is worth a builder because it is one of the four structures the Aether registers, and
 * the picker would otherwise offer it and draw a placeholder. The generator is a random walk -
 * sixty-four steps, each with a fixed drift, each smearing a small diamond of cloud around
 * where it landed - and it is ported as it stands, {@code size} and the cloud block both read
 * from the structure JSON, so a pack that ships a second aercloud out of blue clouds gets it
 * for nothing.</p>
 *
 * <p>Two departures, both deliberate. The starting height is dropped: the mod picks it
 * somewhere in the dimension's lower {@code rangeY} blocks, which decides where the cloud
 * <em>is</em>, not what it looks like, and a display is normalised to its own bounding box
 * anyway. And the extents of each step's diamond are drawn once per step rather than
 * re-rolled on every pass of the loop condition as the mod's happen to be - the edge comes out
 * a little less ragged, and a cloud costs dozens of random draws instead of thousands.</p>
 */
final class LargeAercloudBuilder {

    /** Steps in the walk, and the cloud size when the structure JSON does not say. */
    private static final int STEPS = 64;
    private static final int DEFAULT_SIZE = 3;

    private LargeAercloudBuilder() {
    }

    static void build(AetherAssembly assembly, StructureDefinition definition) {
        BlockState cloud = definition.state("blocks");
        if (cloud == null) {
            return; // the Aether is not installed, or this one is built from a random provider
        }
        int size = Math.max(1, definition.number("size", DEFAULT_SIZE));
        Random random = assembly.random();

        boolean along = random.nextBoolean();
        int x = 0;
        int y = 0;
        int z = 0;
        int xTendency = random.nextInt(3) - 1;
        int zTendency = random.nextInt(3) - 1;

        for (int step = 0; step < STEPS; step++) {
            x += random.nextInt(3) - 1 + xTendency;
            y += random.nextInt(10) == 0 ? random.nextInt(3) - 1 : 0;
            z += along ? random.nextInt(3) - 1 + zTendency : -(random.nextInt(3) - 1 + zTendency);

            int spanX = x + random.nextInt(4) + 3 * size;
            int spanY = y + random.nextInt(1) + 2;
            int spanZ = z + random.nextInt(4) + 3 * size;
            int reach = 4 * size + random.nextInt(2);
            for (int bx = x; bx < spanX; bx++) {
                for (int by = y; by < spanY; by++) {
                    for (int bz = z; bz < spanZ; bz++) {
                        if (Math.abs(bx - x) + Math.abs(by - y) + Math.abs(bz - z) < reach) {
                            assembly.set(new BlockPos(bx, by, bz), cloud);
                        }
                    }
                }
            }
        }
    }
}
