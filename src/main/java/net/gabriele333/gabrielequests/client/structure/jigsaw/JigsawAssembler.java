package net.gabriele333.gabrielequests.client.structure.jigsaw;

import net.gabriele333.gabrielequests.client.structure.StructureData;
import net.gabriele333.gabrielequests.client.structure.StructureLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Assembles a whole jigsaw structure - a village, an ancient city, a bastion, or anything a
 * structure mod builds the same way - out of the {@code .nbt} templates its pools declare,
 * without a world and without world generation.
 *
 * <p>This is a deliberately simplified re-implementation of vanilla's
 * {@code JigsawPlacement}: place the start piece at the origin, then repeatedly take an
 * unconnected jigsaw block, look at the pool it points to, and try each candidate template
 * in each of the four rotations until one has a matching jigsaw block (its {@code name}
 * equals our {@code target}) that would face ours, and whose bounding box does not collide
 * with anything already placed. Accepted pieces contribute their own jigsaw blocks to the
 * queue, until the structure's {@code size} (its expansion depth) is reached.</p>
 *
 * <p><b>What it does not do,</b> because all of it needs the server-side world-generation
 * stack: terrain matching and heightmap projection, block processors (the mossify/rot
 * passes that weather a village), the "expansion hack" that lets vanilla stretch village
 * streets, feature and list pool elements, and entity spawns. The result is therefore the
 * structure's <em>shape</em> - the right rooms connected the right way - rather than a
 * byte-identical copy of one particular generated instance. For a quest-book showcase that
 * is exactly what is wanted, and it has the decisive advantage of being identical on every
 * client without anyone having to visit the structure.</p>
 *
 * <p>Placement is driven by a {@link Random} seeded from the structure id, so every client
 * assembles the same instance and the cached render is stable across sessions.</p>
 */
public final class JigsawAssembler {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /**
     * Hard ceilings on the search, independent of the structure's declared size: a village
     * with {@code size: 6} can otherwise expand into hundreds of pieces, which is neither
     * renderable nor readable as an illustration.
     *
     * <p>The piece count used to be the only cap, at 24, and it was far too blunt: 24 pieces
     * is a whole bastion but only a third of a village, so villages and ancient cities came
     * out visibly half-built while a dungeon of dense rooms was nowhere near the limit. What
     * actually costs anything is <em>blocks</em>, so the real governor is now
     * {@link #MAX_ASSEMBLED_BLOCKS} - a running total of what has been placed - and the piece
     * count is just a loop guard well above what any structure needs. The block budget sits
     * under {@link StructureLoader#MAX_BLOCKS} so an assembly stops growing before it can be
     * rejected outright.</p>
     */
    private static final int MAX_PIECES = 96;
    private static final int MAX_ASSEMBLED_BLOCKS = 110_000;
    private static final int MAX_CANDIDATES_PER_JOINT = 24;

    /**
     * How far an assembly may sprawl, deliberately tighter than what the renderer will accept.
     *
     * <p>The two limits measure different things. {@link StructureLoader#MAX_SIZE} asks "can
     * this be drawn at all", and it is 256 because a baked dungeon really is that big. Sprawl
     * here is not a structure being large, it is the solver wandering: given the extra room,
     * the ancient city grew from 189 to 248 blocks across without gaining a single room worth
     * looking at, and at 248 an icon is showing four pixels per block. 192 is where an
     * assembled structure still reads.</p>
     */
    private static final int MAX_ASSEMBLED_SPAN = 192;

    private static final Rotation[] ROTATIONS = Rotation.values();

    private JigsawAssembler() {
    }

    /** One placed template: which one, how it is turned, and where its origin landed. */
    private record Piece(String templateId, Rotation rotation, BlockPos offset, BoundingBox box) {
    }

    /**
     * The assembly in progress: the pieces placed so far, how many blocks they add up to and
     * the box they occupy. Keeping the running totals here rather than recomputing them is
     * not premature - the block count of a piece means decoding its template, and the loop
     * asks after every single candidate.
     */
    private static final class Assembly {

        private final List<Piece> pieces = new ArrayList<>();
        private int blocks;
        @Nullable
        private BoundingBox box;

        void add(Piece piece, int blockCount) {
            pieces.add(piece);
            blocks += blockCount;
            box = box == null ? piece.box() : encompass(box, piece.box());
        }

        /**
         * Whether adding this box would keep the whole assembly inside the render budget.
         * Checking <em>before</em> placing is what stopped a runaway village from being
         * thrown away wholesale: {@code merge} used to discover the overflow at the end and
         * return nothing at all, so the display fell back to a placeholder and the author saw
         * an empty frame rather than a slightly smaller village.
         */
        boolean fits(BoundingBox candidate) {
            BoundingBox union = box == null ? candidate : encompass(box, candidate);
            return union.getXSpan() <= MAX_ASSEMBLED_SPAN
                    && union.getYSpan() <= MAX_ASSEMBLED_SPAN
                    && union.getZSpan() <= MAX_ASSEMBLED_SPAN;
        }

        boolean hasRoom() {
            return pieces.size() < MAX_PIECES && blocks < MAX_ASSEMBLED_BLOCKS;
        }

        private static BoundingBox encompass(BoundingBox a, BoundingBox b) {
            return new BoundingBox(
                    Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                    Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
        }
    }

    /**
     * A jigsaw block of a placed piece, already resolved to world space. {@code weight} is
     * the size of the biggest thing that could fill it, and drives the order in which joints
     * are served - see {@link #build}.
     */
    private record OpenJoint(BlockPos worldPos, Direction front, Direction top, boolean rollable,
                             String targetName, String poolId, int depth, int weight) {
    }

    /**
     * Assembles the structure, or returns {@code null} when it is not a jigsaw structure,
     * its data is missing, or the result blew the render budget.
     *
     * @param markers keep world-gen marker blocks (structure/jigsaw blocks, barriers, light)
     */
    /**
     * Assembled structures, keyed by (id, markers). Solving is far too expensive to redo on
     * demand: the icon asks for its blocks again whenever it re-renders - and an orbit drag
     * re-renders constantly - so without this the whole solver ran every single frame.
     * A {@code null} value memoises "this one cannot be assembled" so failures are not
     * retried either.
     */
    private static final Map<String, StructureData> CACHE = new HashMap<>();

    @Nullable
    public static synchronized StructureData assemble(String structureId, boolean markers) {
        String key = structureId + "|" + markers;
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        StructureData assembled = null;
        JigsawDefinitions.JigsawStructure definition = JigsawDefinitions.structure(structureId);
        if (definition != null) {
            try {
                assembled = build(structureId, definition, markers);
            } catch (Throwable t) {
                LOGGER.error("[GabrieleQuests/QuestsTools] Failed to assemble jigsaw structure {}", structureId, t);
            }
        }
        CACHE.put(key, assembled);
        return assembled;
    }

    /**
     * The template a structure is built outward from - drawn from its start pool, or, for the
     * pool-less mods, the piece the templates mark themselves. Exposed so a caller that could
     * not get a whole structure out of the solver can still show its root piece rather than
     * nothing at all (see {@code WholeStructures#build}).
     */
    @Nullable
    public static String startTemplate(String structureId) {
        JigsawDefinitions.JigsawStructure definition = JigsawDefinitions.structure(structureId);
        if (definition == null) {
            return null;
        }
        JigsawDefinitions.TemplatePool startPool = definition.startPool().isEmpty() ? null
                : JigsawDefinitions.pool(definition.startPool());
        return startPool != null
                ? pick(startPool, new Random(structureId.hashCode()))
                : JigsawTemplates.findStart(structureId);
    }

    @Nullable
    private static StructureData build(String structureId, JigsawDefinitions.JigsawStructure definition,
                                       boolean markers) {
        Random random = new Random(structureId.hashCode());
        JigsawDefinitions.TemplatePool startPool = definition.startPool().isEmpty() ? null
                : JigsawDefinitions.pool(definition.startPool());
        String startTemplate = startTemplate(structureId);
        if (startTemplate == null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Structure {} has neither a usable start pool nor "
                    + "templates of its own; it is generated in code and cannot be assembled", structureId);
            return null;
        }
        StructureLoader.RawTemplate start = StructureLoader.readRaw(startTemplate);
        if (start == null) {
            return null;
        }

        Assembly assembly = new Assembly();
        // Biggest-first, not first-come-first-served. A village town centre offers its
        // lamp/villager joints before its street joints, and every street then offers five
        // decoration joints before the one "building_entrance" that a house hangs off - so a
        // plain FIFO walk spent the entire piece budget on paths and lamp posts and produced
        // a village with no houses in it. Serving the joints that can hold the biggest piece
        // first builds the village proper and lets the trimmings have what is left.
        PriorityQueue<OpenJoint> queue =
                new PriorityQueue<>(Comparator.comparingInt(OpenJoint::weight).reversed());
        boolean usesPools = startPool != null;
        place(structureId, usesPools, assembly, queue, startTemplate, start, Rotation.NONE, BlockPos.ZERO, 0);

        while (!queue.isEmpty() && assembly.hasRoom()) {
            OpenJoint joint = queue.poll();
            if (joint.depth() >= definition.size()) {
                continue;
            }
            attach(structureId, usesPools, assembly, queue, joint, random);
        }
        LOGGER.info("[GabrieleQuests/QuestsTools] Assembled {} from {} jigsaw pieces ({} blocks placed)",
                structureId, assembly.pieces.size(), assembly.blocks);
        return merge(structureId, assembly.pieces, markers);
    }

    /** Tries every candidate for the joint, in every rotation, until one fits. */
    private static void attach(String structureId, boolean usesPools, Assembly assembly,
                               PriorityQueue<OpenJoint> queue, OpenJoint joint, Random random) {
        List<String> candidates = candidatesFor(structureId, usesPools, joint, random);
        if (candidates.isEmpty()) {
            return;
        }
        // The position the new piece's jigsaw block has to occupy: one step past ours, facing back.
        BlockPos connectionPos = joint.worldPos().relative(joint.front());
        Direction needed = joint.front().getOpposite();

        for (int attempt = 0; attempt < Math.min(candidates.size(), MAX_CANDIDATES_PER_JOINT); attempt++) {
            String candidateId = candidates.get(attempt);
            StructureLoader.RawTemplate candidate = StructureLoader.readRaw(candidateId);
            if (candidate == null) {
                continue;
            }
            for (Rotation rotation : shuffledRotations(random)) {
                for (StructureLoader.RawBlock block : candidate.blocks()) {
                    JigsawInfo info = jigsawInfo(block);
                    if (info == null || !info.name().equals(joint.targetName())) {
                        continue;
                    }
                    if (!canAttach(joint, info, rotation, needed)) {
                        continue;
                    }
                    // Offset so this candidate jigsaw block lands exactly on connectionPos.
                    BlockPos rotatedJigsaw = rotate(block.pos(), rotation);
                    BlockPos offset = connectionPos.subtract(rotatedJigsaw);
                    BoundingBox box = boxOf(candidate.size(), rotation, offset);
                    if (collides(assembly.pieces, box) || !assembly.fits(box)) {
                        continue;
                    }
                    place(structureId, usesPools, assembly, queue, candidateId, candidate, rotation,
                            offset, joint.depth() + 1);
                    return;
                }
            }
        }
    }

    /** Records a piece and queues every jigsaw block it brings. */
    private static void place(String structureId, boolean usesPools, Assembly assembly,
                              PriorityQueue<OpenJoint> queue, String templateId,
                              StructureLoader.RawTemplate template, Rotation rotation,
                              BlockPos offset, int depth) {
        assembly.add(new Piece(templateId, rotation, offset, boxOf(template.size(), rotation, offset)),
                template.blocks().size());
        for (StructureLoader.RawBlock block : template.blocks()) {
            JigsawInfo info = jigsawInfo(block);
            if (info == null || info.pool().isEmpty()) {
                continue;
            }
            BlockPos worldPos = rotate(block.pos(), rotation).offset(offset);
            queue.add(new OpenJoint(worldPos, rotation.rotate(info.front()),
                    rotation.rotate(info.top()), info.rollable(), info.target(),
                    info.pool(), depth, weightOf(structureId, usesPools, info)));
        }
    }

    /**
     * How big the biggest piece that could fill this joint is, in blocks. Cached per pool
     * (or per target name, for the pool-less path) because it means decoding the candidate
     * templates - which we would do anyway the moment one is actually placed.
     */
    private static final Map<String, Integer> WEIGHTS = new HashMap<>();

    private static int weightOf(String structureId, boolean usesPools, JigsawInfo info) {
        String key = usesPools ? "pool:" + info.pool() : structureId + "#" + info.target();
        Integer cached = WEIGHTS.get(key);
        if (cached != null) {
            return cached;
        }
        int best = 0;
        JigsawDefinitions.TemplatePool pool = JigsawDefinitions.pool(info.pool());
        if (pool != null) {
            for (JigsawDefinitions.PoolEntry entry : pool.entries()) {
                best = Math.max(best, blockCount(entry.templateId()));
            }
        } else if (!usesPools) {
            for (String candidate : JigsawTemplates.candidatesFor(structureId, info.target())) {
                best = Math.max(best, blockCount(candidate));
            }
        }
        WEIGHTS.put(key, best);
        return best;
    }

    /**
     * Flattens the placed pieces into one block map, dropping the last-placed ones until the
     * result is inside the render budget.
     *
     * <p>Placement already refuses anything that would blow the bounding box, so this is the
     * safety net for the one limit it cannot see coming - the block count of a dense
     * assembly. Trimming rather than giving up matters: the pieces are placed root-first, so
     * what gets dropped is the outermost trimming, and the author gets a slightly smaller
     * structure instead of the empty placeholder the old "over budget, return null" produced.
     * </p>
     */
    @Nullable
    private static StructureData merge(String structureId, List<Piece> pieces, boolean markers) {
        List<Piece> kept = pieces;
        while (!kept.isEmpty()) {
            Merged merged = flatten(kept, markers);
            if (merged == null) {
                // Not a budget problem and not worth trimming for: every piece resolved to
                // markers and structure void, so fewer pieces cannot help.
                LOGGER.warn("[GabrieleQuests/QuestsTools] Assembled {} has no visible blocks in any of its "
                        + "{} pieces", structureId, kept.size());
                return null;
            }
            if (StructureLoader.withinBudget(structureId, merged.size(), false)
                    && merged.blocks().size() <= StructureLoader.MAX_BLOCKS) {
                return merged.toData();
            }
            LOGGER.warn("[GabrieleQuests/QuestsTools] Assembled {} is over the render budget with {} pieces "
                    + "({} blocks, {}x{}x{}); dropping the last one", structureId, kept.size(),
                    merged.blocks().size(), merged.size().getX(), merged.size().getY(), merged.size().getZ());
            kept = kept.subList(0, kept.size() - 1);
        }
        return null;
    }

    /** A flattened assembly, still to be measured against the budget. */
    private record Merged(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> blockEntities,
                          BlockPos min, BlockPos size) {

        /** Shifts everything so the minimum corner is the origin, as the renderer expects. */
        StructureData toData() {
            Map<BlockPos, BlockState> shifted = new HashMap<>(blocks.size());
            blocks.forEach((pos, state) -> shifted.put(pos.subtract(min), state));
            Map<BlockPos, CompoundTag> shiftedEntities = new HashMap<>(blockEntities.size());
            blockEntities.forEach((pos, tag) -> shiftedEntities.put(pos.subtract(min), tag));
            return StructureData.of(shifted, shiftedEntities, size);
        }
    }

    @Nullable
    private static Merged flatten(List<Piece> pieces, boolean markers) {
        if (pieces.isEmpty()) {
            return null;
        }
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (Piece piece : pieces) {
            StructureLoader.RawTemplate template = StructureLoader.readRaw(piece.templateId());
            if (template == null) {
                continue;
            }
            for (StructureLoader.RawBlock block : template.blocks()) {
                BlockState state = resolve(block, markers);
                if (state == null) {
                    continue;
                }
                BlockPos pos = rotate(block.pos(), piece.rotation()).offset(piece.offset());
                blocks.put(pos, state.rotate(piece.rotation()));
                if (block.nbt() != null && jigsawInfo(block) == null) {
                    blockEntities.put(pos, block.nbt());
                } else {
                    // A jigsaw block replaced by its final_state must not keep the jigsaw
                    // block entity, or the BER phase would try to render a marker.
                    blockEntities.remove(pos);
                }
                minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        return new Merged(blocks, blockEntities, new BlockPos(minX, minY, minZ),
                new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1));
    }

    /**
     * The state a block contributes to the merged structure: a jigsaw block becomes its
     * {@code final_state} (usually a path block or structure void, which is how vanilla
     * hides the marker), everything else stays as-is. {@code null} means "draw nothing".
     */
    @Nullable
    private static BlockState resolve(StructureLoader.RawBlock block, boolean markers) {
        JigsawInfo info = jigsawInfo(block);
        if (info != null && !markers) {
            BlockState finalState = parseState(info.finalState());
            return finalState == null || StructureLoader.isHidden(finalState, false) ? null : finalState;
        }
        return StructureLoader.isHidden(block.state(), markers) ? null : block.state();
    }

    /** The jigsaw fields we care about, or {@code null} when the block is not a jigsaw. */
    private record JigsawInfo(Direction front, Direction top, boolean rollable, String name,
                              String target, String pool, String finalState) {
    }

    @Nullable
    private static JigsawInfo jigsawInfo(StructureLoader.RawBlock block) {
        if (!(block.state().getBlock() instanceof JigsawBlock) || block.nbt() == null) {
            return null;
        }
        FrontAndTop orientation = block.state().getValue(JigsawBlock.ORIENTATION);
        CompoundTag tag = block.nbt();
        // Vanilla defaults an absent joint to rollable for a vertical jigsaw and aligned for
        // a horizontal one (JigsawBlockEntity#getJointType).
        String joint = tag.getString("joint");
        boolean rollable = joint.isEmpty()
                ? orientation.front().getAxis().isVertical()
                : joint.equals("rollable");
        return new JigsawInfo(orientation.front(), orientation.top(), rollable,
                tag.getString("name"), tag.getString("target"), tag.getString("pool"),
                tag.contains("final_state") ? tag.getString("final_state") : "minecraft:air");
    }

    @Nullable
    private static BlockState parseState(String description) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), description, false)
                    .blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Vanilla's {@code JigsawBlock.canAttach}: the two jigsaw blocks must face each other
     * and, unless the joint is rollable, their <em>top</em> directions must agree too.
     *
     * <p>Skipping the top rule (as a first cut did) leaves the rotation of a vertically
     * joined piece unconstrained, so a room that should keep one orientation could be placed
     * turned - which is exactly how a bastion or a trial chamber ends up looking scrambled
     * even though every piece is individually "connected".</p>
     */
    private static boolean canAttach(OpenJoint joint, JigsawInfo candidate, Rotation rotation,
                                     Direction needed) {
        if (rotation.rotate(candidate.front()) != needed) {
            return false;
        }
        // Vanilla reads the joint type off the source jigsaw only, not off both.
        if (joint.rollable()) {
            return true;
        }
        return rotation.rotate(candidate.top()) == joint.top();
    }

    /** Rotates a template-local position about the template origin, as vanilla does. */
    private static BlockPos rotate(BlockPos pos, Rotation rotation) {
        return StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO);
    }

    /** The rotated, offset bounding box of a template of the given size. */
    private static BoundingBox boxOf(BlockPos size, Rotation rotation, BlockPos offset) {
        BlockPos a = rotate(BlockPos.ZERO, rotation).offset(offset);
        BlockPos b = rotate(new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), rotation)
                .offset(offset);
        return BoundingBox.fromCorners(a, b);
    }

    /**
     * A piece this flat is ground: a street plot, a path, a courtyard. Buildings are meant to
     * stand on those, so they do not block a placement.
     */
    private static final int FLAT_PIECE_HEIGHT = 2;

    /**
     * Whether a candidate box overlaps something already placed.
     *
     * <p>Two kinds of piece are deliberately ignored, both of them scaffolding rather than
     * building:</p>
     * <ul>
     *   <li><b>Flat plots.</b> A village street is a 16&#215;2&#215;16 plate whose
     *       {@code building_entrance} jigsaw sits <em>inside</em> that footprint, so a house
     *       attached to it necessarily overlaps it - measured, every one of the 24 candidate
     *       houses was rejected for exactly this, which is why villages came out as bare road
     *       networks. Since pieces are merged in placement order, the house's own floor wins
     *       over the path underneath, and houses still cannot overlap each other.</li>
     *   <li><b>Pieces that draw nothing.</b> A template of pure structure void reserves a
     *       volume for something else to be built in. The pillager outpost is exactly that: a
     *       16&#215;30&#215;16 {@code base_plate} of structure void whose only job is to hold
     *       the jigsaw the watchtower hangs off - so treating it as solid rejected the tower,
     *       and the outpost merged down to nothing at all and drew a placeholder. A piece
     *       with no visible blocks cannot occlude anything, so it must not block anything.</li>
     * </ul>
     */
    private static boolean collides(List<Piece> pieces, BoundingBox box) {
        for (Piece piece : pieces) {
            if (piece.box().getYSpan() <= FLAT_PIECE_HEIGHT || !drawsAnything(piece.templateId())) {
                continue;
            }
            if (piece.box().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a template contributes a single block once markers and void are dropped. */
    private static final Map<String, Boolean> DRAWS = new HashMap<>();

    private static boolean drawsAnything(String templateId) {
        Boolean cached = DRAWS.get(templateId);
        if (cached != null) {
            return cached;
        }
        boolean draws = false;
        StructureLoader.RawTemplate template = StructureLoader.readRaw(templateId);
        if (template != null) {
            for (StructureLoader.RawBlock block : template.blocks()) {
                if (resolve(block, false) != null) {
                    draws = true;
                    break;
                }
            }
        }
        DRAWS.put(templateId, draws);
        return draws;
    }

    /**
     * The templates that may fill a joint, best first.
     *
     * <p>When the joint names a pool that exists, that pool decides (weighted, shuffled) -
     * the vanilla path. Otherwise the candidates come from the template index, and they are
     * ordered <b>largest first</b>. That ordering is what keeps a code-driven structure
     * recognisable: Twilight Forest's foyer alone offers sixty jigsaws for decorative
     * shelves, and a fair draw would spend the whole piece budget on shelves before the
     * tower ever got a floor. Preferring the big pieces builds the structure first and lets
     * the trimmings use whatever budget is left.</p>
     */
    private static List<String> candidatesFor(String structureId, boolean usesPools,
                                              OpenJoint joint, Random random) {
        JigsawDefinitions.TemplatePool pool = JigsawDefinitions.pool(joint.poolId());
        if (pool != null) {
            List<String> picks = new ArrayList<>();
            for (int i = 0; i < MAX_CANDIDATES_PER_JOINT; i++) {
                String pick = pick(pool, random);
                if (pick != null) {
                    picks.add(pick);
                }
            }
            return picks;
        }
        if (usesPools) {
            // This structure is pool-driven and this particular joint points at a pool that
            // does not exist (minecraft:empty, or one a data pack never shipped). That means
            // "nothing attaches here", exactly as in vanilla - not "go looking". Building the
            // template index here would decode every template of the structure for nothing,
            // which is what made a trial chamber take the better part of a second.
            return List.of();
        }
        List<String> candidates = new ArrayList<>(
                JigsawTemplates.candidatesFor(structureId, joint.targetName()));
        candidates.sort((a, b) -> Integer.compare(blockCount(b), blockCount(a)));
        return candidates;
    }

    private static int blockCount(String templateId) {
        StructureLoader.RawTemplate template = StructureLoader.readRaw(templateId);
        return template == null ? 0 : template.blocks().size();
    }

    /** Weighted pick from a pool. */
    @Nullable
    private static String pick(JigsawDefinitions.TemplatePool pool, Random random) {
        int total = 0;
        for (JigsawDefinitions.PoolEntry entry : pool.entries()) {
            total += entry.weight();
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (JigsawDefinitions.PoolEntry entry : pool.entries()) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry.templateId();
            }
        }
        return pool.entries().get(0).templateId();
    }

    private static Rotation[] shuffledRotations(Random random) {
        Rotation[] shuffled = ROTATIONS.clone();
        for (int i = shuffled.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Rotation tmp = shuffled[i];
            shuffled[i] = shuffled[j];
            shuffled[j] = tmp;
        }
        return shuffled;
    }
}
