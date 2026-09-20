package net.gabriele333.gabrielequests.client.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Map;

/**
 * A structure template decoded into what the renderer needs: the blocks, the block-entity
 * tags stored alongside them (so chests, signs and banners render with their real contents)
 * and the bounding-box size. Positions run {@code 0..size-1} on each axis, which is what
 * {@link net.gabriele333.gabrielequests.client.render.SceneRenderer} expects.
 */
public record StructureData(Map<BlockPos, BlockState> blocks,
                            Map<BlockPos, CompoundTag> blockEntities,
                            BlockPos size) {

    /**
     * Wraps maps the caller has just built and will not touch again.
     *
     * <p>Use this rather than {@code Map.copyOf}. The guarantee is the same - callers must not
     * mutate what they hand over, and nothing can mutate it through this record - but
     * {@code Map.copyOf} allocates a second full map, which on a baked dungeon means copying a
     * hundred and forty thousand entries for nothing. Measured on Twilight Forest's aurora
     * palace, the copies were most of an eleven-second stall the first time the display drew.
     * </p>
     */
    public static StructureData of(Map<BlockPos, BlockState> blocks,
                                   Map<BlockPos, CompoundTag> blockEntities, BlockPos size) {
        return new StructureData(Collections.unmodifiableMap(blocks),
                Collections.unmodifiableMap(blockEntities), size);
    }
}
