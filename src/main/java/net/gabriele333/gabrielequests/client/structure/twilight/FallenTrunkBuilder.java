package net.gabriele333.gabrielequests.client.structure.twilight;

import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * The fallen trunk: a hollow log lying on its side, mossed over.
 *
 * <p>{@code FallenTrunkPiece} draws it as a ring of logs in cross-section - kept where the
 * distance from the axis is inside the radius but outside {@code radius / 2}, using the
 * mod's own squarish distance ({@code max + min * 0.5}, the same one the hollow tree trunk
 * uses) - extruded along the length, with both ends eroded away a block at a time by coin
 * flips. All of that is reproduced here; the log block and the length come from the structure
 * JSON.</p>
 *
 * <p>The mod rolls one of three radii (1, 2 and 4) per placement. This shows the largest,
 * which is the one that gets the longer {@code big_trunk_length} and is the only one a
 * player would call a landmark: the radius-1 variant is four logs in a row.</p>
 */
final class FallenTrunkBuilder {

    /** Blocks at each end that erode away, from {@code FallenTrunkPiece}. */
    private static final int ERODED_LENGTH = 2;
    private static final float MOSS_CHANCE = 0.44F;
    /** The largest of the mod's three radii - see the class note. */
    private static final int RADIUS = 4;

    private FallenTrunkBuilder() {
    }

    @Nullable
    static TfScene build(String structureId, TfDefinition definition) {
        BlockState log = definition.state("log", "twilightforest:twilight_oak_log");
        if (log == null) {
            return null;
        }
        // Lying down: the mod turns the log's axis along the trunk before placing it.
        log = BlockStates.with(log, "axis", "z");
        BlockState moss = BlockStates.state("twilightforest:moss_patch");
        int length = definition.number("big_trunk_length", 0.5, 25);

        TfScene scene = new TfScene(structureId);
        Random random = scene.random();
        int hollow = RADIUS / 2;
        int diameter = RADIUS * 2;

        for (int dx = 0; dx <= diameter; dx++) {
            for (int dy = 0; dy <= diameter; dy++) {
                int distance = distance(dx, dy);
                if (distance > RADIUS || distance <= hollow) {
                    continue;
                }
                for (int dz = ERODED_LENGTH; dz < length - 1 - ERODED_LENGTH; dz++) {
                    scene.set(dx, dy, dz, log);
                }
                // Both ends fray: each further block only appears if the coin says so, and
                // the first tail stops the run - which is why no two ends look alike.
                for (int dz = ERODED_LENGTH - 1; dz >= 0; dz--) {
                    if (random.nextBoolean()) {
                        break;
                    }
                    scene.set(dx, dy, dz, log);
                }
                for (int dz = length - 1 - ERODED_LENGTH; dz < length - 1; dz++) {
                    if (random.nextBoolean()) {
                        break;
                    }
                    scene.set(dx, dy, dz, log);
                }
            }
        }
        if (moss != null) {
            growMoss(scene, random, moss, diameter, length);
        }
        return scene;
    }

    /** Moss on whatever ended up with open sky above it, at the mod's 44%. */
    private static void growMoss(TfScene scene, Random random, BlockState moss, int diameter, int length) {
        // Top down, so a patch is never grown on the patch below it.
        for (int dx = 0; dx <= diameter; dx++) {
            for (int dy = diameter; dy >= 0; dy--) {
                for (int dz = 0; dz < length; dz++) {
                    if (!scene.isEmpty(dx, dy, dz) && scene.isEmpty(dx, dy + 1, dz)
                            && random.nextFloat() <= MOSS_CHANCE) {
                        scene.set(dx, dy + 1, dz, moss);
                    }
                }
            }
        }
    }

    /** The mod's squarish cross-section distance: a rounded octagon rather than a circle. */
    private static int distance(int dx, int dy) {
        int ax = Math.abs(dx - RADIUS);
        int ay = Math.abs(dy - RADIUS);
        return (int) (Math.max(ax, ay) + (Math.min(ax, ay) * 0.5));
    }
}
