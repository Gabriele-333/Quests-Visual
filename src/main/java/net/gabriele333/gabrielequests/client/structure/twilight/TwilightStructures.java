package net.gabriele333.gabrielequests.client.structure.twilight;

import net.gabriele333.gabrielequests.client.structure.StructureData;
import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The Twilight Forest integration: whole structures the mod builds in Java, rebuilt here from
 * its own generators so they can be shown in a quest book.
 *
 * <p><b>Why this exists.</b> The generic paths cover a structure only if there is data to
 * read: the jigsaw assembler needs template pools (or at least templates wired with jigsaw
 * blocks), and a composite recipe needs {@code .nbt} files to arrange. Twilight Forest ships
 * templates for four of its twenty-one structures. The rest - hollow hills, the hedge maze,
 * the hollow trees, the fallen trunk, the mushroom tower and the big dungeons - exist only as
 * Java that runs during world generation, so from the outside they look exactly like a
 * structure that is not there: the picker offered nothing and a display of one drew a
 * placeholder. No amount of work on the generic side can change that, which is what makes a
 * dedicated integration the only way in.</p>
 *
 * <p><b>How faithful it is.</b> The builders are ports of the mod's own generator maths, not
 * impressions of screenshots: the hollow hill's two cosine curves, the hedge maze's
 * recursive-backtracker and its wall spacing, the trunk cross-sections, the mushroom cap's
 * sine sweep. Everything the mod declares in data - {@code hill_size}, the tree's height and
 * radius, the trunk's length, the log and leaf blocks - is read from its structure JSON
 * rather than copied into constants, so these follow the mod across a version that retunes
 * them and a pack that adds its own variant gets a correct display for free. What is left out
 * is what needs a world to exist: terrain, loot, spawners, block processors, and the
 * randomness that makes each in-world instance different. Each builder's class note says
 * where it simplifies.</p>
 *
 * <p><b>Dispatch is by structure type, not by id.</b> Builders are keyed by the {@code type}
 * field of {@code worldgen/structure/<id>.json}, which is how the three hollow hills and both
 * hollow trees come out of one entry each, and how a pack that adds a fourth hollow hill gets
 * it listed without anything here changing.</p>
 *
 * <p>Nothing here is a compile-time or hard runtime dependency: blocks are resolved by
 * registry name, so with Twilight Forest absent every builder produces nothing, the entries
 * disappear from the picker, and an existing display falls back to a placeholder - the same
 * contract the display multiblock and display portal keep.</p>
 */
public final class TwilightStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Structure type -> builder. See the class note on why this is keyed by type. */
    private static final Map<String, BiFunction<String, TfDefinition, TfScene>> BUILDERS = Map.of(
            "twilightforest:hollow_hill", HollowHillBuilder::build,
            "twilightforest:hedge_maze", HedgeMazeBuilder::build,
            "twilightforest:fallen_trunk", FallenTrunkBuilder::build,
            "twilightforest:hollow_tree", HollowTreeBuilder::build,
            "twilightforest:mushroom_tower", MushroomTowerBuilder::build);

    @Nullable
    private static List<String> ids;
    /** Built results; a {@code null} value memoises "this one cannot be built". */
    private static final Map<String, StructureData> CACHE = new HashMap<>();

    private TwilightStructures() {
    }

    /** Every structure this integration can build, sorted. */
    public static synchronized List<String> ids() {
        if (ids == null) {
            List<String> found = new ArrayList<>();
            for (String id : StructureIndex.structureIds()) {
                TfDefinition definition = TfDefinition.of(id);
                if (definition != null && BUILDERS.containsKey(definition.type())) {
                    found.add(id);
                }
            }
            ids = List.copyOf(found);
            if (!found.isEmpty()) {
                LOGGER.info("[GabrieleQuests/QuestsTools] {} Twilight Forest structures can be built from "
                        + "their generators", found.size());
            }
        }
        return ids;
    }

    public static boolean has(String id) {
        return ids().contains(id);
    }

    /**
     * Builds the structure, or {@code null} when there is no builder for it, its blocks are
     * not installed, or the result is over the render budget.
     *
     * <p>{@code markers} is accepted for symmetry with the other whole-structure sources and
     * ignored: nothing built here is a world-gen marker, so there is nothing to hide.</p>
     */
    @Nullable
    public static synchronized StructureData build(String id, boolean markers) {
        if (CACHE.containsKey(id)) {
            return CACHE.get(id);
        }
        StructureData built = null;
        try {
            TfDefinition definition = TfDefinition.of(id);
            BiFunction<String, TfDefinition, TfScene> builder =
                    definition == null ? null : BUILDERS.get(definition.type());
            if (builder != null) {
                TfScene scene = builder.apply(id, definition);
                built = scene == null ? null : scene.finish(id);
            }
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to build Twilight Forest structure {}", id, t);
        }
        if (built == null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Twilight Forest structure {} produced nothing "
                    + "(its blocks are probably not installed)", id);
        }
        CACHE.put(id, built);
        return built;
    }
}
