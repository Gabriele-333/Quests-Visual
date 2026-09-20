package net.gabriele333.gabrielequests.client.structure.twilight;

import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The three hollow hills: a grassy dome with a cavern under it.
 *
 * <p>The shape is Twilight Forest's own, not an eyeballed dome.
 * {@code HollowHillComponent} defines the cavern with two cosine curves over the distance
 * from the centre - a floor that rises towards the rim and a ceiling that falls to meet it -
 * and sizes everything off {@code hill_size}, which the structure JSON declares:</p>
 *
 * <pre>
 *   radius  = ((hill_size * 2 + 1) * 8) - 6     // 18 / 34 / 50
 *   hdiam   =  (hill_size * 2 + 1) * 16         // 48 / 80 / 112
 *   floor   =  hill_size * 2 - cos(d/hdiam * PI) * (hdiam / 20) + 1
 *   ceiling =                   cos(d/hdiam * PI) * (hdiam / 4)
 * </pre>
 *
 * <p>What the mod does <em>not</em> place is the hill itself: in the world the dome is
 * terrain, carved to shape by the structure's density function, so there is no block list to
 * copy. The roof here is therefore the ceiling curve given a skin - stone with dirt and grass
 * on top - which is exactly the surface the density function produces.</p>
 *
 * <p>Only a shell is built: a roof a few blocks thick and a floor slab, with the cavern
 * hollow between them. Filling the dome solid would be four to five times the blocks for an
 * identical picture, since nothing inside a closed hill can be seen from outside anyway - and
 * a large hill is 100 blocks across, so that difference is the difference between comfortably
 * inside the render budget and scraping it.</p>
 */
final class HollowHillBuilder {

    /** Blocks of rock over the cavern ceiling, the outermost two of which are soil and grass. */
    private static final int ROOF = 3;
    /** Blocks of rock under the cavern floor. */
    private static final int FLOOR = 2;
    /**
     * How far short of the rim the cavern stops. Left to run all the way out, the two curves
     * still leave a couple of blocks of headroom at {@code radius}, so the dome would end in
     * an open ring of cave mouth all the way round instead of meeting the ground.
     */
    private static final int RIM = 6;

    private HollowHillBuilder() {
    }

    @Nullable
    static TfScene build(String structureId, TfDefinition definition) {
        int hillSize = Math.clamp(definition.number("hill_size", 0, 1), 1, 4);
        int radius = ((hillSize * 2 + 1) * 8) - 6;
        int hdiam = (hillSize * 2 + 1) * 16;

        BlockState stone = BlockStates.state("minecraft:stone");
        BlockState dirt = BlockStates.state("minecraft:dirt");
        BlockState grass = BlockStates.state("minecraft:grass_block");
        if (stone == null) {
            return null;
        }

        TfScene scene = new TfScene(structureId);
        int caveRadius = radius - RIM;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) {
                    continue;
                }
                int floor = Mth.floor(floorHeight(hillSize, hdiam, distance) + 0.25F);
                int ceiling = Mth.ceil(ceilingHeight(hdiam, distance));
                int top = ceiling + ROOF;
                int bottom = floor - FLOOR;
                boolean hollow = distance <= caveRadius && ceiling - floor >= 2;

                for (int y = bottom; y <= top; y++) {
                    if (hollow && y > floor && y < ceiling) {
                        continue;
                    }
                    scene.set(dx, y, dz, y == top ? grass : y >= top - 1 ? dirt : stone);
                }
            }
        }
        return scene;
    }

    private static float floorHeight(int hillSize, int hdiam, double distance) {
        return (hillSize * 2) - Mth.cos((float) (distance / hdiam) * Mth.PI) * (hdiam / 20F) + 1;
    }

    private static float ceilingHeight(int hdiam, double distance) {
        return Mth.cos((float) (distance / hdiam) * Mth.PI) * (hdiam / 4F);
    }
}
