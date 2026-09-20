package net.gabriele333.gabrielequests.client.render;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Minimal read-only level backed by a block map, just enough for
 * {@code ModelBlockRenderer.tesselateBlock} to render blocks with neighbour-aware face
 * culling (interior faces between adjacent blocks are skipped - the main reason a hollow
 * 18&#178; shell, or a dungeon room, stays cheap to tesselate) and ambient occlusion.
 *
 * <p>Light is full-bright: both {@link #getBrightness} and {@link #getRawBrightness} are
 * overridden so the {@link #getLightEngine()} default path is never taken (there is no
 * light engine to give out). Shading uses the vanilla directional constants so the render
 * looks like a block in a level rather than a flat paper cutout.</p>
 *
 * <p>Shared by the display multiblock and the display structure. Block entities are not
 * served here: the renderer creates its own fake ones in its BER phase, and handing them
 * out during tesselation would make baked models query state the fakes do not have.</p>
 */
public final class SceneLevel implements BlockAndTintGetter {

    // Plains-ish tints, since there is no biome to resolve against. Only the display
    // structure hits these (no multiblock block is biome-tinted), where they keep grass,
    // leaves and water from rendering as washed-out white.
    private static final int GRASS_TINT = 0x91BD59;
    private static final int FOLIAGE_TINT = 0x77AB2F;
    private static final int WATER_TINT = 0x3F76E4;

    private final Map<BlockPos, BlockState> blocks;
    private final int height;

    public SceneLevel(Map<BlockPos, BlockState> blocks, int height) {
        this.blocks = blocks;
        this.height = height;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    @Nullable
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) {
            return 1.0F;
        }
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    @Override
    public LevelLightEngine getLightEngine() {
        // Never reached: getBrightness/getRawBrightness below cover every caller.
        throw new UnsupportedOperationException("SceneLevel has no light engine");
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return true;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        if (colorResolver == BiomeColors.GRASS_COLOR_RESOLVER) {
            return GRASS_TINT;
        }
        if (colorResolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
            return FOLIAGE_TINT;
        }
        if (colorResolver == BiomeColors.WATER_COLOR_RESOLVER) {
            return WATER_TINT;
        }
        return -1; // white: leave any other (modded) resolver's block untinted
    }

    @Override
    public int getHeight() {
        return Math.max(height, 1);
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }
}
