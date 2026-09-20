package net.gabriele333.gabrielequests.client.structure.jigsaw;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the two data-pack JSONs the jigsaw assembler needs, straight from the mod files:
 * {@code worldgen/structure/<id>.json} (is this structure jigsaw-assembled, which pool does
 * it start from, how far may it expand) and {@code worldgen/template_pool/<id>.json} (which
 * templates may be drawn, with what weight).
 *
 * <p>Parsed with plain Gson rather than the vanilla codecs on purpose: the codecs want a
 * {@code RegistryOps} bound to the structure/template-pool registries, which only exist on
 * the logical server - the whole point of this feature is to work from the jars on any
 * client. We only read the handful of fields the assembler uses and ignore the rest
 * (biomes, spawn overrides, terrain adaptation, processors...), which is why an assembled
 * structure is a faithful <em>shape</em> rather than a byte-identical copy of what world
 * generation would produce.</p>
 */
public final class JigsawDefinitions {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** A {@code minecraft:jigsaw} structure definition. */
    public record JigsawStructure(String startPool, int size) {
    }

    /** One template a pool may place. */
    public record PoolEntry(String templateId, int weight) {
    }

    /** A template pool: its entries plus the pool it falls back to when nothing fits. */
    public record TemplatePool(List<PoolEntry> entries) {
    }

    private static final Map<String, JigsawStructure> STRUCTURES = new HashMap<>();
    private static final Map<String, TemplatePool> POOLS = new HashMap<>();

    private JigsawDefinitions() {
    }

    /**
     * The jigsaw definition of a structure, or {@code null} when the structure is unknown,
     * unreadable, or not of type {@code minecraft:jigsaw} (a structure the mod generates in
     * Java - see the composite recipes for those).
     */
    @Nullable
    public static synchronized JigsawStructure structure(String id) {
        if (STRUCTURES.containsKey(id)) {
            return STRUCTURES.get(id);
        }
        JigsawStructure parsed = null;
        try {
            parsed = readStructure(id);
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not read structure definition {}", id, t);
        }
        STRUCTURES.put(id, parsed);
        return parsed;
    }

    /** The entries of a template pool, or {@code null} when it is unknown or unreadable. */
    @Nullable
    public static synchronized TemplatePool pool(String id) {
        if (POOLS.containsKey(id)) {
            return POOLS.get(id);
        }
        TemplatePool parsed = null;
        try {
            parsed = readPool(id);
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not read template pool {}", id, t);
        }
        POOLS.put(id, parsed);
        return parsed;
    }

    /**
     * True when this structure id can be assembled: its definition exists, and either it
     * names a start pool or {@link JigsawTemplates} can find a start piece among the
     * templates filed under its own path.
     */
    public static boolean isAssemblable(String id) {
        JigsawStructure definition = structure(id);
        if (definition == null) {
            return false;
        }
        return !definition.startPool().isEmpty() || JigsawTemplates.findStart(id) != null;
    }

    /**
     * How deep to expand a structure whose definition does not declare a {@code size},
     * because it is not a vanilla jigsaw structure. Deep enough for a tower or a camp to
     * come out whole, shallow enough that the piece cap does the rest.
     */
    private static final int DEFAULT_SIZE = 8;

    @Nullable
    private static JigsawStructure readStructure(String id) throws Exception {
        JsonObject json = readJson(StructureIndex.structurePath(id));
        if (json == null) {
            return null;
        }
        int size = json.has("size") ? json.get("size").getAsInt() : DEFAULT_SIZE;
        String startPool = asString(json.get("start_pool"));
        if (startPool != null) {
            return new JigsawStructure(normalize(startPool), size);
        }
        // No start pool. Either a vanilla structure generated in Java (mansion, monument -
        // no templates at all, so the start search below finds nothing and we give up), or a
        // mod that drives the jigsaw system from its own code instead of from template pools.
        // Twilight Forest is the latter: its templates carry perfectly good jigsaw blocks,
        // they are just wired up in Java, so there is nothing to read here but plenty to
        // solve. Those get an empty start pool and the assembler finds the root piece by the
        // "structure_start" marker the templates themselves carry.
        return new JigsawStructure("", size);
    }

    @Nullable
    private static TemplatePool readPool(String id) throws Exception {
        JsonObject json = readJson(StructureIndex.poolPath(id));
        if (json == null || !json.has("elements")) {
            return null;
        }
        List<PoolEntry> entries = new ArrayList<>();
        JsonArray elements = json.getAsJsonArray("elements");
        for (JsonElement raw : elements) {
            if (!raw.isJsonObject()) {
                continue;
            }
            JsonObject wrapper = raw.getAsJsonObject();
            int weight = wrapper.has("weight") ? wrapper.get("weight").getAsInt() : 1;
            JsonElement element = wrapper.get("element");
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            String location = location(element.getAsJsonObject());
            if (location != null) {
                entries.add(new PoolEntry(normalize(location), Math.max(weight, 1)));
            }
        }
        return entries.isEmpty() ? null : new TemplatePool(List.copyOf(entries));
    }

    /**
     * The template a pool element places, or {@code null} for the element kinds there is
     * nothing to place for ({@code empty}, and {@code feature}, which runs a configured
     * feature we have no world to run it in).
     *
     * <p>A {@code list_pool_element} nests several elements that all land at the same spot -
     * a building plus the overlay that weathers it - and this takes the <b>first</b>, which
     * is the building. Skipping list elements entirely (as a first cut did) is what made the
     * pillager outpost draw nothing at all: its {@code towers} pool holds exactly one
     * element, the list of {@code watchtower} + {@code watchtower_overgrown}, so with that
     * skipped the pool was empty, no tower was ever placed, and all that survived the merge
     * were the structure-void plates the outpost sits on.</p>
     */
    @Nullable
    private static String location(JsonObject element) {
        String location = asString(element.get("location"));
        if (location != null) {
            return location;
        }
        JsonArray nested = element.getAsJsonArray("elements");
        if (nested != null) {
            for (JsonElement raw : nested) {
                if (raw.isJsonObject()) {
                    String inner = location(raw.getAsJsonObject());
                    if (inner != null) {
                        return inner;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static JsonObject readJson(@Nullable Path path) throws Exception {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
    }

    @Nullable
    private static String asString(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** Data-pack ids may omit the namespace, which then means {@code minecraft}. */
    private static String normalize(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }
}
