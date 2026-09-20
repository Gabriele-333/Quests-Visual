package net.gabriele333.gabrielequests.client.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * A structure under construction: the block map a port of a mod's own generator pastes
 * templates into, plus the piece geometry its layout maths asks about.
 *
 * <p>This is the shared half of the "mod stitches its templates together in Java" case - the
 * Aether and the Gaia Dimension both ship templates with neither pools nor jigsaw blocks, so
 * both need the same paste, the same bounding boxes and the same budget check, and differ only
 * in how a piece name becomes a template id ({@link #templateId}).</p>
 *
 * <p>Pieces are placed exactly the way {@code TemplateStructurePiece} places them - every block
 * run through {@link StructureTemplate#transform} with the piece's rotation and then offset by
 * the piece position - so an offset copied out of a mod's generator lands where the mod puts
 * it. That is also why this does not reuse the composite-recipe paste, which re-normalises a
 * rotated template back to positive coordinates: convenient for a hand-written part list,
 * wrong for maths that expects vanilla's bounding boxes.</p>
 *
 * <p>Rotation pivots are ignored, and that is exact rather than approximate: a display always
 * fixes the structure's rotation at {@link Rotation#NONE}, and with no rotation
 * {@code StructureTemplate.transform} returns the position unchanged whatever the pivot is. So
 * the pieces that declare one (the Aether's boss rooms, every Gaia tower floor) are placed
 * exactly as their mod places them, and the pieces that are genuinely rotated relative to the
 * structure use a zero pivot in their mod too.</p>
 *
 * <p>Randomness is one {@link Random} seeded from the structure id, the rule the jigsaw
 * assembler and the Twilight builders already follow: the layout is a roll of the dice in the
 * world, and a quest book wants every client to see the same structure, this session and the
 * next.</p>
 */
public class TemplateAssembly {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    protected final String structureId;
    /** The mod this assembly is building for; only ever used to make the log lines readable. */
    private final String mod;
    private final boolean markers;
    private final Random random;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();

    public TemplateAssembly(String structureId, String mod, boolean markers) {
        this.structureId = structureId;
        this.mod = mod;
        this.markers = markers;
        this.random = new Random(structureId.hashCode());
    }

    public Random random() {
        return random;
    }

    /**
     * The template id a builder's piece name refers to. The default is "the name already is
     * one"; a mod that files its pieces somewhere predictable overrides this so its builders
     * can name rooms and nothing else.
     */
    protected String templateId(String piece) {
        return piece;
    }

    /** A piece's declared size, or {@code null} when the mod is not installed. */
    @Nullable
    public BlockPos size(String piece) {
        StructureLoader.RawTemplate template = StructureLoader.readRaw(templateId(piece));
        return template == null ? null : template.size();
    }

    /**
     * Where a piece would sit, as {@code TemplateStructurePiece} computes it - the box the
     * mod's own layout maths measures from. {@code null} when the piece is not installed.
     */
    @Nullable
    public BoundingBox box(String piece, BlockPos pos, Rotation rotation) {
        BlockPos size = size(piece);
        if (size == null) {
            return null;
        }
        BlockPos min = transform(BlockPos.ZERO, rotation);
        BlockPos max = transform(new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), rotation);
        return BoundingBox.fromCorners(min, max).move(pos.getX(), pos.getY(), pos.getZ());
    }

    /** Pastes a piece; a no-op (and {@code false}) when it is not installed. */
    public boolean place(String piece, BlockPos pos, Rotation rotation) {
        StructureLoader.RawTemplate template = StructureLoader.readRaw(templateId(piece));
        if (template == null) {
            return false;
        }
        for (StructureLoader.RawBlock block : template.blocks()) {
            if (StructureLoader.isHidden(block.state(), markers)) {
                continue;
            }
            BlockPos at = transform(block.pos(), rotation).offset(pos);
            blocks.put(at, block.state().rotate(rotation));
            if (block.nbt() != null) {
                blockEntities.put(at, block.nbt());
            } else {
                // A later piece overlapping an earlier one replaces its block entity too,
                // exactly as placing it in the world would.
                blockEntities.remove(at);
            }
        }
        return true;
    }

    /** One block, for the structures that are not made of templates at all. */
    public void set(BlockPos pos, @Nullable BlockState state) {
        if (state != null) {
            blocks.put(pos, state);
        }
    }

    private static BlockPos transform(BlockPos pos, Rotation rotation) {
        return StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO);
    }

    /**
     * Shifts everything so the minimum corner is the origin and checks the render budget;
     * {@code null} when the builder produced nothing (the mod is absent) or something too big
     * to draw.
     */
    @Nullable
    public StructureData finish() {
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
            LOGGER.warn("[GabrieleQuests/QuestsTools] {} structure {} is {} blocks in a {}x{}x{} box; "
                    + "too big to render", mod, structureId, blocks.size(), size.getX(), size.getY(), size.getZ());
            return null;
        }
        Map<BlockPos, BlockState> shifted = HashMap.newHashMap(blocks.size());
        blocks.forEach((pos, state) -> shifted.put(pos.subtract(min), state));
        Map<BlockPos, CompoundTag> shiftedEntities = HashMap.newHashMap(blockEntities.size());
        blockEntities.forEach((pos, tag) -> shiftedEntities.put(pos.subtract(min), tag));
        LOGGER.info("[GabrieleQuests/QuestsTools] Built {} structure {} ({} blocks, {}x{}x{})",
                mod, structureId, shifted.size(), size.getX(), size.getY(), size.getZ());
        return StructureData.of(shifted, shiftedEntities, size);
    }
}
