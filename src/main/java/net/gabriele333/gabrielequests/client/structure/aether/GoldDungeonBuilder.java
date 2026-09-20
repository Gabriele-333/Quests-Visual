package net.gabriele333.gabrielequests.client.structure.aether;

import net.gabriele333.gabrielequests.client.structure.StructureDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Random;

/**
 * The Aether's Gold Dungeon: the floating island with the Sun Spirit's hall buried in it.
 *
 * <p>Unlike the other two this is barely a maze at all - the island is a single 38-block
 * template, the boss room is centred inside it, and a tunnel leaves its door and breaks out of
 * the island's flank. The only random part is the ring of smaller islands: {@code stubcount}
 * (from the structure JSON) plus up to four more, each dropped at a random angle roughly 17 to
 * 19 blocks out and a little below the rim, which is what gives the island its ragged edge.</p>
 *
 * <p>The "gumdrop caves" are left out. They are eighteen tapered tubes of air the mod carves
 * through the island after placing it, and they only exist to be found by a player flying
 * around inside; from the outside they cost a hollowed silhouette for holes nobody can see in
 * an icon. The golden oak trees that the mod grows on top afterwards are out for the same
 * reason as every other feature call: running one needs a world.</p>
 */
final class GoldDungeonBuilder {

    private static final String ISLAND = "island";
    private static final String STUB = "stub";
    private static final String BOSS_ROOM = "boss_room";
    private static final String TUNNEL = "tunnel";

    /** Stub islands when the structure JSON does not say. */
    private static final int DEFAULT_STUBS = 8;

    private GoldDungeonBuilder() {
    }

    static void build(AetherAssembly assembly, StructureDefinition definition) {
        BlockPos origin = BlockPos.ZERO;
        if (!assembly.place(ISLAND, origin, Rotation.NONE)) {
            return; // the Aether is not installed
        }
        BoundingBox island = assembly.box(ISLAND, origin, Rotation.NONE);
        BlockPos stubSize = assembly.size(STUB);
        BlockPos bossSize = assembly.size(BOSS_ROOM);
        BlockPos tunnelSize = assembly.size(TUNNEL);
        if (island == null || stubSize == null || bossSize == null || tunnelSize == null) {
            return;
        }
        BlockPos center = island.getCenter();
        Random random = assembly.random();

        // The ring of smaller islands. Each is placed by its centre, hence the half-size shift.
        BlockPos stubOffset = new BlockPos(-stubSize.getX() / 2, -stubSize.getY() / 2, -stubSize.getZ() / 2);
        int stubs = definition.number("stubcount", DEFAULT_STUBS) + random.nextInt(5);
        for (int i = 0; i < stubs; i++) {
            float angle = random.nextFloat() * (float) (Math.PI * 2);
            float distance = ((random.nextFloat() * 0.125F) + 0.7F) * 24.0F;
            BlockPos at = center.offset(
                    Mth.floor(Math.cos(angle) * distance),
                    -Mth.floor(24.0 * random.nextFloat() * 0.3),
                    Mth.floor(-Math.sin(angle) * distance));
            assembly.place(STUB, at.offset(stubOffset), Rotation.NONE);
        }

        // The hall sits centred in the island, one block back along its facing.
        Rotation rotation = Rotation.NONE;
        Direction direction = rotation.rotate(Direction.SOUTH);
        BlockPos bossPos = center.offset(
                -bossSize.getX() / 2 - direction.getStepX(),
                -bossSize.getY() / 2,
                -bossSize.getZ() / 2 - direction.getStepZ());
        BoundingBox bossRoom = assembly.box(BOSS_ROOM, bossPos, rotation);
        if (bossRoom == null) {
            return;
        }
        // The tunnel first, then the hall over it: the hall's doorway blocks have to win.
        BlockPos tunnelPos = AetherGeometry.tunnelFromOddRoom(bossRoom, direction, tunnelSize.getX())
                .offset(direction.getStepX() * 3, 1, direction.getStepZ() * 3);
        assembly.place(TUNNEL, tunnelPos, rotation);
        assembly.place(BOSS_ROOM, bossPos, rotation);
    }
}
