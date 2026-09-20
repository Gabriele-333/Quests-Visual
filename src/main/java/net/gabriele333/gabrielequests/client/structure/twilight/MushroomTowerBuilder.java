package net.gabriele333.gabrielequests.client.structure.twilight;

import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The mushroom tower: a stem-walled tower under a red mushroom cap, with four shorter towers
 * around it, each capped too and joined to the middle by a plank bridge.
 *
 * <p>The cap is the mod's, formula and all. {@code TowerRoofMushroomComponent} sweeps a
 * radius through a sine as it climbs - {@code sin((y + h/1.2) / (h * 2.05) * PI) * size / 2}
 * with a 0.9 hollow factor and the top three layers solid - and sizes itself off the tower it
 * crowns with a 1.6 overhang, which is where a mushroom tower's absurdly wide brim comes
 * from. The tower body uses the mod's other squarish distance ({@code max + min * 0.4}), so
 * it is the same rounded octagon of mushroom stem, floored every four blocks.</p>
 *
 * <p>Where this simplifies: the mod grows the satellite towers by jigsaw-ish recursion, at
 * whatever heights and angles fit the terrain, occasionally three deep. Four wings at fixed
 * bearings and staggered heights is the shape that recursion converges on and is what reads
 * as a mushroom tower in an icon.</p>
 */
final class MushroomTowerBuilder {

    /** The mod's constants: main tower width and the spacing between floors. */
    private static final int MAIN_SIZE = 15;
    private static final int FLOOR_HEIGHT = 4;
    /** Wing towers are four narrower than the middle, as {@code makeBridge} asks for. */
    private static final int WING_SIZE = MAIN_SIZE - 4;
    /** How wide the cap overhangs the tower it sits on. */
    private static final float OVERHANG = 1.6F;
    /**
     * Gap between the main tower and a wing, spanned by the bridge. Chosen so a wing lands
     * just under the rim of the middle cap: any closer and the 31-block main cap swallows it
     * whole, any further and the group stops reading as one building.
     */
    private static final int BRIDGE = 12;

    private MushroomTowerBuilder() {
    }

    @Nullable
    static TfScene build(String structureId, TfDefinition definition) {
        // MushroomTowerDecorator's palette, by id so nothing here depends on Twilight Forest.
        BlockState stem = BlockStates.state("minecraft:mushroom_stem");
        BlockState cap = BlockStates.state("minecraft:red_mushroom_block");
        BlockState floor = BlockStates.state("minecraft:oak_planks");
        if (stem == null || cap == null) {
            return null;
        }
        // A huge-mushroom block draws the skin of whichever sides are switched on; the mod
        // turns top and bottom off on the stem so a column of them looks like one stalk.
        stem = BlockStates.with(BlockStates.with(stem, "up", "false"), "down", "false");

        TfScene scene = new TfScene(structureId);
        int mainHeight = 8 + FLOOR_HEIGHT; // the middle of the mod's 8 / 12 / 16
        tower(scene, stem, floor, 0, 0, MAIN_SIZE, mainHeight);
        mushroomCap(scene, cap, 0, mainHeight + 2, 0, MAIN_SIZE);

        // Four wings, staggered so the silhouette is not four identical posts.
        int distance = MAIN_SIZE / 2 + BRIDGE + WING_SIZE / 2;
        int[] heights = {9, 13, 17, 21};
        Direction[] bearings = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        for (int i = 0; i < bearings.length; i++) {
            Direction bearing = bearings[i];
            int wingX = MAIN_SIZE / 2 + bearing.getStepX() * distance - WING_SIZE / 2;
            int wingZ = MAIN_SIZE / 2 + bearing.getStepZ() * distance - WING_SIZE / 2;
            int height = heights[i];
            tower(scene, stem, floor, wingX, wingZ, WING_SIZE, height);
            mushroomCap(scene, cap, wingX, height + 2, wingZ, WING_SIZE);
            bridge(scene, stem, floor, bearing, distance, Math.min(height, mainHeight) - 3);
        }
        return scene;
    }

    /** One tower: a hollow rounded-octagon stem wall with a plank floor every four blocks. */
    private static void tower(TfScene scene, BlockState stem, @Nullable BlockState floor,
                              int originX, int originZ, int size, int height) {
        int radius = size / 2;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int ax = Math.abs(dx - radius);
                int az = Math.abs(dz - radius);
                int distance = (int) (Math.max(ax, az) + (Math.min(ax, az) * 0.4));
                if (distance > radius) {
                    continue;
                }
                if (distance == radius) {
                    for (int y = 0; y <= height; y++) {
                        scene.set(originX + dx, y, originZ + dz, stem);
                    }
                } else {
                    for (int y = 0; y <= height; y += FLOOR_HEIGHT) {
                        scene.set(originX + dx, y, originZ + dz, floor);
                    }
                }
            }
        }
    }

    /**
     * The cap, straight out of {@code TowerRoofMushroomComponent}: {@code height} equals the
     * tower's width, the overhang widens it to {@code height + 2 * overhang}, and each layer
     * is a ring whose radius follows the sine. Below the top three layers only the rim is
     * drawn, which is what leaves the underside of a mushroom cap hollow.
     */
    private static void mushroomCap(TfScene scene, BlockState cap, int towerX, int baseY, int towerZ,
                                    int towerSize) {
        int height = towerSize;
        int overhang = (int) (height * OVERHANG);
        int size = height + (overhang * 2);
        int centerX = towerX + towerSize / 2;
        int centerZ = towerZ + towerSize / 2;

        for (int y = 0; y <= height; y++) {
            int radius = (int) (Mth.sin((y + height / 1.2F) / (height * 2.05F) * 3.14F) * size / 2F);
            int hollow = (height - y) < 3 ? -1 : Mth.floor(radius * 0.9F);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    float distance = Mth.sqrt(dx * dx + dz * dz);
                    if (distance <= radius + 0.5F && distance > hollow) {
                        scene.set(centerX + dx, baseY + y, centerZ + dz, cap);
                    }
                }
            }
        }
    }

    /** A three-wide walkway from the main tower out to a wing, railed with stem. */
    private static void bridge(TfScene scene, BlockState stem, @Nullable BlockState floor,
                               Direction bearing, int distance, int y) {
        int centerX = MAIN_SIZE / 2;
        int centerZ = MAIN_SIZE / 2;
        for (int step = MAIN_SIZE / 2; step <= distance; step++) {
            int x = centerX + bearing.getStepX() * step;
            int z = centerZ + bearing.getStepZ() * step;
            // Across the walkway: sideways is whichever horizontal axis the bearing is not on.
            int sideX = bearing.getStepZ();
            int sideZ = bearing.getStepX();
            scene.set(x, y, z, floor);
            scene.set(x + sideX, y, z + sideZ, stem);
            scene.set(x - sideX, y, z - sideZ, stem);
        }
    }
}
