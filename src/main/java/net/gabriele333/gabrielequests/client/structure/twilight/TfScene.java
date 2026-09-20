package net.gabriele333.gabrielequests.client.structure.twilight;

import net.gabriele333.gabrielequests.client.structure.StructureData;
import net.gabriele333.gabrielequests.client.structure.StructureLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A block map under construction, for the procedural Twilight Forest builders.
 *
 * <p>Coordinates may be negative and may be written in any order; {@link #finish} shifts the
 * result so its minimum corner is the origin, which is what the renderer wants. Writing a
 * {@code null} state is a no-op, which is what lets a builder say
 * {@code set(x, y, z, definition.state("log", ...))} without checking first whether Twilight
 * Forest is even installed - if a block id resolves to nothing, that part of the structure
 * simply is not drawn, and a builder whose main material is missing produces an empty scene
 * that {@link #finish} turns into "cannot be rendered".</p>
 */
final class TfScene {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    /**
     * The builders' one source of randomness. Seeded from the structure id so every client
     * builds the same instance and the cached render is stable across sessions - the same
     * rule the jigsaw assembler follows.
     */
    private final Random random;

    TfScene(String structureId) {
        this.random = new Random(structureId.hashCode());
    }

    Random random() {
        return random;
    }

    void set(int x, int y, int z, @Nullable BlockState state) {
        if (state != null) {
            blocks.put(new BlockPos(x, y, z), state);
        }
    }

    /** Writes only where nothing has been written yet, for decoration over structure. */
    void setIfEmpty(int x, int y, int z, @Nullable BlockState state) {
        if (state != null) {
            blocks.putIfAbsent(new BlockPos(x, y, z), state);
        }
    }

    boolean isEmpty(int x, int y, int z) {
        return !blocks.containsKey(new BlockPos(x, y, z));
    }

    /** Inclusive box fill, corners in any order. */
    void fill(int x0, int y0, int z0, int x1, int y1, int z1, @Nullable BlockState state) {
        if (state == null) {
            return;
        }
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    set(x, y, z, state);
                }
            }
        }
    }

    /** A solid ball, used for the leaf clusters at the end of a branch. */
    void ball(int cx, int cy, int cz, double radius, @Nullable BlockState state) {
        if (state == null) {
            return;
        }
        int r = (int) Math.ceil(radius);
        double squared = radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz <= squared) {
                        setIfEmpty(cx + dx, cy + dy, cz + dz, state);
                    }
                }
            }
        }
    }

    /** A straight run of blocks between two points, for branches and roots. */
    void line(BlockPos from, BlockPos to, @Nullable BlockState state) {
        if (state == null) {
            return;
        }
        int steps = Math.max(Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getY() - from.getY())),
                Math.abs(to.getZ() - from.getZ()));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            set((int) Math.round(from.getX() + (to.getX() - from.getX()) * t),
                    (int) Math.round(from.getY() + (to.getY() - from.getY()) * t),
                    (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t),
                    state);
        }
    }

    /**
     * Twilight Forest's polar offset ({@code FeatureLogic.translate}): {@code angle} is a
     * full turn in 0..1 and {@code tilt} is 0 straight up, 0.5 horizontal, 1 straight down.
     * Every branch and root position in the hollow tree is expressed in these terms, so
     * keeping the same convention is what makes the crown come out in the right shape.
     */
    static BlockPos translate(BlockPos from, double length, double angle, double tilt) {
        double rAngle = angle * 2.0 * Math.PI;
        double rTilt = tilt * Math.PI;
        return from.offset(
                (int) Math.round(Math.sin(rAngle) * Math.sin(rTilt) * length),
                (int) Math.round(Math.cos(rTilt) * length),
                (int) Math.round(Math.cos(rAngle) * Math.sin(rTilt) * length));
    }

    /**
     * Normalises to the origin and checks the render budget; {@code null} when the builder
     * produced nothing or something too big to draw.
     */
    @Nullable
    StructureData finish(String structureId) {
        if (blocks.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        BlockPos min = new BlockPos(minX, minY, minZ);
        BlockPos size = new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        if (!StructureLoader.withinBudget(structureId, size) || blocks.size() > StructureLoader.MAX_BLOCKS) {
            return null;
        }
        Map<BlockPos, BlockState> shifted = new HashMap<>(blocks.size());
        blocks.forEach((pos, state) -> shifted.put(pos.subtract(min), state));
        LOGGER.info("[GabrieleQuests/QuestsTools] Built Twilight Forest structure {} ({} blocks, {}x{}x{})",
                structureId, shifted.size(), size.getX(), size.getY(), size.getZ());
        return StructureData.of(shifted, Map.of(), size);
    }
}
