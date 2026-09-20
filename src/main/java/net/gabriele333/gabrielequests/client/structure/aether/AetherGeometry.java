package net.gabriele333.gabrielequests.client.structure.aether;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where a tunnel meets a room. A line-for-line port of the Aether's {@code BlockLogicUtil},
 * because these two expressions are the whole reason its dungeons line up: every corridor,
 * every branch and the gold dungeon's exit are positioned by them, and an "obviously
 * equivalent" rewrite lands a block off and leaves a seam in the wall.
 *
 * <p>The two differ only in how they halve the room: an even-width room has no middle column,
 * so the doorway is offset by one to stay symmetric, while an odd-width room is centred on
 * its middle column.</p>
 */
final class AetherGeometry {

    private AetherGeometry() {
    }

    /** The entry point of a {@code width}-wide tunnel leaving an even-width room. */
    static BlockPos tunnelFromEvenRoom(BoundingBox box, Direction direction, int width) {
        int offsetFromCenter = (((direction.getAxis() == Direction.Axis.X ? box.getZSpan() : box.getXSpan()) + 1) >> 1);
        int sidedOffset = width >> 1;
        int x = direction.getStepX() * offsetFromCenter - direction.getStepZ() * sidedOffset
                - Math.max(0, direction.getStepX()) + Math.min(0, direction.getStepZ());
        int z = direction.getStepZ() * offsetFromCenter + direction.getStepX() * sidedOffset
                - Math.max(0, direction.getStepZ()) - Math.max(0, direction.getStepX());
        return box.getCenter().offset(x, -(box.getYSpan() >> 1), z);
    }

    /** The same for an odd-width room. */
    static BlockPos tunnelFromOddRoom(BoundingBox box, Direction direction, int width) {
        int offsetFromCenter = (direction.getAxis() == Direction.Axis.X ? box.getZSpan() : box.getXSpan()) >> 1;
        int sidedOffset = width >> 1;
        int x = direction.getStepX() * offsetFromCenter - direction.getStepZ() * sidedOffset
                - Math.max(0, direction.getStepX());
        int z = direction.getStepZ() * offsetFromCenter + direction.getStepX() * sidedOffset
                - Math.max(0, direction.getStepZ());
        return box.getCenter().offset(x, -(box.getYSpan() >> 1), z);
    }
}
