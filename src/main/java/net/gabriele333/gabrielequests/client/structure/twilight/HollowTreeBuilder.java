package net.gabriele333.gabrielequests.client.structure.twilight;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * The hollow tree (and its swamp variant): an enormous hollow trunk with a crown of branches
 * and a spread of roots.
 *
 * <p>The trunk is the mod's, block for block - {@code HollowTreeTrunk} keeps the ring where
 * the squarish distance {@code max + min * 0.5} is inside the radius but outside
 * {@code radius / 2}, which is why a hollow tree is a tube you can climb inside rather than a
 * pillar. The crown follows the same recipe as {@code buildFullCrown}: four rings of branches
 * (a wide one at the bottom of the crown, a middle one, a short one at the very top and a
 * near-vertical one), plus a handful of small branches up the trunk and two rings of roots
 * below, each branch aimed by the mod's polar {@code (angle, tilt)} convention - see
 * {@link TfScene#translate}.</p>
 *
 * <p>Where this simplifies: a real branch is drawn by its own structure piece, tapering, with
 * its own leaf cluster and sometimes a small dungeon inside; here each is a straight run of
 * wood with a ball of leaves at the tip. At the size a quest-book icon renders, that reads the
 * same. Height and radius come from the structure JSON - the mod's range is 32-95 tall, and
 * the low end of it is taken, because a 95-block tree in a square frame is a green thread.</p>
 */
final class HollowTreeBuilder {

    /** Where in the mod's declared ranges to land; see {@code TfDefinition#number}. */
    private static final double HEIGHT_BIAS = 0.2;
    private static final double RADIUS_BIAS = 1.0;

    private HollowTreeBuilder() {
    }

    @Nullable
    static TfScene build(String structureId, TfDefinition definition) {
        BlockState log = definition.state("log", "twilightforest:twilight_oak_log");
        if (log == null) {
            return null;
        }
        BlockState wood = definition.state("wood", "twilightforest:twilight_oak_wood");
        BlockState root = definition.state("root", "twilightforest:root");
        BlockState leaves = definition.state("leaves", "twilightforest:twilight_oak_leaves");

        int radius = Math.clamp(definition.number("radius", RADIUS_BIAS, 4), 1, 8);
        int height = Math.clamp(definition.number("height", HEIGHT_BIAS, 45), 16, 128);

        TfScene scene = new TfScene(structureId);
        Random random = scene.random();
        // The trunk's axis, in the same local coordinates the mod uses (its bounding box is
        // one block wider than the trunk on each side, hence the +1).
        int cx = radius + 1;
        int cz = radius + 1;

        trunk(scene, log, radius, height);
        roots(scene, random, root, wood, radius, cx, cz);
        crown(scene, random, wood, leaves, radius, height, cx, cz);
        return scene;
    }

    /** The hollow tube, plus a few blocks of it continuing below ground. */
    private static void trunk(TfScene scene, BlockState log, int radius, int height) {
        int hollow = radius / 2;
        for (int dx = 0; dx <= 2 * radius; dx++) {
            for (int dz = 0; dz <= 2 * radius; dz++) {
                int ax = Math.abs(dx - radius);
                int az = Math.abs(dz - radius);
                int distance = (int) (Math.max(ax, az) + (Math.min(ax, az) * 0.5));
                if (distance > radius) {
                    continue;
                }
                int x = dx + 1;
                int z = dz + 1;
                if (distance > hollow) {
                    for (int y = 0; y <= height; y++) {
                        scene.set(x, y, z, log);
                    }
                }
                // The mod fills the whole footprint down to the ground; a few blocks is
                // enough to sit the tree on something rather than have it float.
                for (int y = -1; y >= -3; y--) {
                    scene.set(x, y, z, log);
                }
            }
        }
    }

    /** Two rings of roots splaying outwards and down, as {@code HollowTreeTrunk} places them. */
    private static void roots(TfScene scene, Random random, @Nullable BlockState root,
                              @Nullable BlockState wood, int radius, int cx, int cz) {
        ring(scene, random, root == null ? wood : root, null, radius, cx, cz, 4, 6, 0.75, 3, 5, 0);
        ring(scene, random, root == null ? wood : root, null, radius, cx, cz, 2, 8, 0.9, 3, 5, 0);
    }

    /**
     * The four branch rings of {@code buildFullCrown}. The crown is as wide as
     * {@code radius * 4 + 4} and starts that far below the top, so a fat trunk carries a
     * proportionally huge canopy - which is most of what makes a hollow tree look like one.
     */
    private static void crown(TfScene scene, Random random, @Nullable BlockState wood,
                              @Nullable BlockState leaves, int radius, int height, int cx, int cz) {
        int crown = radius * 4 + 4;
        int count = radius + 2;
        ring(scene, random, wood, leaves, radius, cx, cz, height - crown, crown, 0.35, count, count + 2, 4);
        ring(scene, random, wood, leaves, radius, cx, cz, height - (crown / 2), crown, 0.28, count, count + 2, 3);
        ring(scene, random, wood, leaves, radius, cx, cz, height, crown, 0.15, 2, 4, 4);
        ring(scene, random, wood, leaves, radius, cx, cz, height, crown / 2, 0.05, count, count + 2, 3);

        // 3-5 stray small branches on the way up, exactly as addChildren does.
        int strays = random.nextInt(3) + 3;
        for (int i = 0; i <= strays; i++) {
            int at = (int) (height * random.nextDouble() * 0.9) + (height / 10);
            branch(scene, wood, leaves, radius, cx, cz, at, 4, random.nextDouble(), 0.35, 2);
        }
    }

    /**
     * One ring of branches around the trunk: {@code min..max} of them, evenly spaced from a
     * random start angle, all at the same height, tilt and length.
     */
    private static void ring(TfScene scene, Random random, @Nullable BlockState wood,
                             @Nullable BlockState leaves, int radius, int cx, int cz,
                             int at, int length, double tilt, int min, int max, int leafRadius) {
        int count = random.nextInt(max - min + 1) + min;
        double spacing = 1.0 / count;
        double offset = random.nextDouble();
        for (int i = 0; i <= count; i++) {
            branch(scene, wood, leaves, radius, cx, cz, at, length, i * spacing + offset, tilt, leafRadius);
        }
    }

    /** One branch: from the trunk's surface out to wherever the angle and tilt point. */
    private static void branch(TfScene scene, @Nullable BlockState wood, @Nullable BlockState leaves,
                               int radius, int cx, int cz, int at, int length, double angle,
                               double tilt, int leafRadius) {
        BlockPos from = TfScene.translate(new BlockPos(cx, at, cz), radius, angle, 0.5);
        BlockPos to = TfScene.translate(from, length, angle, tilt);
        scene.line(from, to, wood);
        if (leafRadius > 0) {
            scene.ball(to.getX(), to.getY(), to.getZ(), leafRadius, leaves);
        }
    }
}
