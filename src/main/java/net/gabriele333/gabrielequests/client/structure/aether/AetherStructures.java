package net.gabriele333.gabrielequests.client.structure.aether;

import net.gabriele333.gabrielequests.client.structure.StructureData;
import net.gabriele333.gabrielequests.client.structure.StructureDefinition;
import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The Aether integration: its four structures, assembled here from its own generators.
 *
 * <p><b>Why it needs one.</b> The Aether ships thirty-odd {@code .nbt} templates and not a
 * single {@code template_pool}, and its templates carry no jigsaw blocks either - the dungeons
 * are stitched together by Java at generation time, a graph of rooms for the bronze one, a
 * 3x3x3 grid for the silver one. So both generic paths come up empty: there is nothing for the
 * assembler to solve and nothing for its template fallback to index. Before this, picking
 * "▣ Bronze dungeon" in the structure picker drew that structure's largest room and nothing
 * else, which is not a dungeon. (The individual rooms have always worked as single pieces, and
 * still do; this is about the whole.)</p>
 *
 * <p><b>How faithful.</b> Each builder is a port of the mod's own layout code - the bronze
 * dungeon's directed graph, the silver dungeon's recursive room walk, the gold island's ring of
 * stubs - reading the numbers it is tuned with ({@code maxrooms}, {@code stubcount},
 * {@code size}) from the mod's structure JSON rather than from constants here. What they leave
 * out is what needs a world: terrain fitting, loot, spawners, block processors, and the
 * features the mod plants afterwards. Every builder's class note says exactly where it
 * simplifies and why.</p>
 *
 * <p><b>Dispatch is by structure type</b>, like the Twilight Forest integration, so a pack that
 * registers a second dungeon of an existing type gets a display without anything here
 * changing. Nothing in this package is a compile-time or hard runtime dependency: templates and
 * blocks are looked up by id, so with the Aether absent every builder produces nothing, the
 * entries disappear from the picker, and an existing display falls back to a placeholder.</p>
 */
public final class AetherStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Structure type -> builder. See the class note on why this is keyed by type. */
    private static final Map<String, BiConsumer<AetherAssembly, StructureDefinition>> BUILDERS = Map.of(
            "aether:bronze_dungeon", BronzeDungeonBuilder::build,
            "aether:silver_dungeon", SilverDungeonBuilder::build,
            "aether:gold_dungeon", GoldDungeonBuilder::build,
            "aether:large_aercloud", LargeAercloudBuilder::build);

    @Nullable
    private static List<String> ids;
    /** Built results keyed by (id, markers); a {@code null} value memoises a failure. */
    private static final Map<String, StructureData> CACHE = new HashMap<>();

    private AetherStructures() {
    }

    /** Every structure this integration can build, sorted. */
    public static synchronized List<String> ids() {
        if (ids == null) {
            List<String> found = new ArrayList<>();
            for (String id : StructureIndex.structureIds()) {
                StructureDefinition definition = StructureDefinition.of(id);
                if (definition != null && BUILDERS.containsKey(definition.type())) {
                    found.add(id);
                }
            }
            ids = List.copyOf(found);
            if (!found.isEmpty()) {
                LOGGER.info("[GabrieleQuests/QuestsTools] {} Aether structures can be built from "
                        + "their generators", found.size());
            }
        }
        return ids;
    }

    public static boolean has(String id) {
        return ids().contains(id);
    }

    /**
     * Builds the structure, or {@code null} when there is no builder for it, its templates are
     * not installed, or the result is over the render budget.
     */
    @Nullable
    public static synchronized StructureData build(String id, boolean markers) {
        String key = id + "|" + markers;
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        StructureData built = null;
        try {
            StructureDefinition definition = StructureDefinition.of(id);
            BiConsumer<AetherAssembly, StructureDefinition> builder =
                    definition == null ? null : BUILDERS.get(definition.type());
            if (builder != null) {
                AetherAssembly assembly = new AetherAssembly(id, markers);
                builder.accept(assembly, definition);
                built = assembly.finish();
            }
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to build Aether structure {}", id, t);
        }
        if (built == null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Aether structure {} produced nothing "
                    + "(its templates are probably not installed)", id);
        }
        CACHE.put(key, built);
        return built;
    }
}
