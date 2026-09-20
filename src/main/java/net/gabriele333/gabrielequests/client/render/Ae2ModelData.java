package net.gabriele333.gabrielequests.client.render;

import appeng.block.crafting.AbstractCraftingUnitBlock;
import appeng.block.qnb.QnbFormedState;
import appeng.block.qnb.QuantumBaseBlock;
import appeng.blockentity.crafting.CraftingCubeModelData;
import appeng.blockentity.qnb.QuantumBridgeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.EnumSet;
import java.util.Map;

/**
 * AE2-only hook, referenced exclusively behind a {@code ModList.isLoaded("ae2")} guard in
 * {@link SceneRenderer} (AE2 is a {@code compileOnly} dependency - this class simply
 * never loads when the mod is absent).
 *
 * <p>AE2's "formed" models (crafting cubes with {@code formed=true}, quantum bridge with
 * {@code formed=true}) read their connectivity from ModelData that the real game gets from
 * block entities ({@code CraftingCubeModelData.CONNECTIONS},
 * {@code QuantumBridgeBlockEntity.FORMED_STATE}). Our fake render level has no block
 * entities, so without this the formed models degrade to the unconnected/unformed look.
 * Here we compute the same data straight from the scene's block map: a neighbour that is
 * part of the same block family counts as connected. That works for a generated multiblock
 * and equally for an AE2 room captured in a structure file.</p>
 */
public final class Ae2ModelData {

    private Ae2ModelData() {
    }

    public static void augment(Map<BlockPos, BlockState> blocks, Map<BlockPos, ModelData> out) {
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            Block block = entry.getValue().getBlock();
            if (block instanceof AbstractCraftingUnitBlock) {
                EnumSet<Direction> connections = EnumSet.noneOf(Direction.class);
                for (Direction dir : Direction.values()) {
                    BlockState neighbour = blocks.get(pos.relative(dir));
                    if (neighbour != null && neighbour.getBlock() instanceof AbstractCraftingUnitBlock) {
                        connections.add(dir);
                    }
                }
                out.put(pos, CraftingCubeModelData.create(connections));
            } else if (block instanceof QuantumBaseBlock) {
                EnumSet<Direction> adjacent = EnumSet.noneOf(Direction.class);
                for (Direction dir : Direction.values()) {
                    BlockState neighbour = blocks.get(pos.relative(dir));
                    if (neighbour != null && neighbour.getBlock() instanceof QuantumBaseBlock) {
                        adjacent.add(dir);
                    }
                }
                // In a formed 3x3 ring exactly the corner blocks have two neighbours
                // (edge blocks touch two corners plus the link, the link touches four).
                out.put(pos, ModelData.builder()
                        .with(QuantumBridgeBlockEntity.FORMED_STATE,
                                new QnbFormedState(adjacent, adjacent.size() == 2, true))
                        .build());
            }
        }
    }
}
