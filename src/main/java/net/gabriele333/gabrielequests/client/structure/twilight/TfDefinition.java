package net.gabriele333.gabrielequests.client.structure.twilight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The parts of a {@code worldgen/structure/<id>.json} the Twilight Forest builders need: its
 * {@code type}, the numbers it configures the generator with, and the blocks it builds from.
 *
 * <p>Reading the mod's own JSON rather than hard-coding the numbers is what makes this an
 * integration instead of an imitation. Twilight Forest declares the hollow tree's height and
 * radius, its log/wood/root/leaf blocks, the fallen trunk's length, and each hollow hill's
 * {@code hill_size}, all in data - so the builders come out right, they follow the mod
 * across a version that retunes them, and a pack that ships a fourth hollow hill size or a
 * hollow tree of dark wood gets a correct display for free. Dispatch works the same way:
 * builders are keyed by the {@code type} field, which is why {@code swamp_hollow_tree} and
 * the three hollow hills need no entries of their own.</p>
 *
 * <p>Parsed with plain Gson for the same reason as {@code JigsawDefinitions}: the vanilla
 * codecs want registries that only exist on the logical server, and this has to work on any
 * client. Blocks go through {@link BlockStates} - <b>ids only</b> - so nothing here is a
 * compile-time or hard runtime dependency on Twilight Forest.</p>
 */
final class TfDefinition {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Parsed definitions, keyed by structure id; {@code null} values memoise "not readable". */
    private static final Map<String, TfDefinition> CACHE = new HashMap<>();

    private final String type;
    private final JsonObject json;

    private TfDefinition(String type, JsonObject json) {
        this.type = type;
        this.json = json;
    }

    /** The definition of a structure, or {@code null} when there is no readable JSON for it. */
    @Nullable
    static synchronized TfDefinition of(String structureId) {
        if (CACHE.containsKey(structureId)) {
            return CACHE.get(structureId);
        }
        TfDefinition parsed = null;
        try {
            Path path = StructureIndex.structurePath(structureId);
            if (path != null && Files.isRegularFile(path)) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (element.isJsonObject()) {
                        JsonObject object = element.getAsJsonObject();
                        String type = string(object.get("type"));
                        if (type != null) {
                            parsed = new TfDefinition(type, object);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not read the definition of {}", structureId, t);
        }
        CACHE.put(structureId, parsed);
        return parsed;
    }

    String type() {
        return type;
    }

    /**
     * An integer field, whether it is a plain number ({@code hill_size}) or one of vanilla's
     * {@code IntProvider} forms ({@code height}, {@code radius}, {@code length}).
     *
     * <p>A range has to collapse to one value because a display is one fixed picture, and the
     * choice is deliberate rather than random: {@code bias} says where in the range to land,
     * 0 for the smallest and 1 for the largest. A hollow tree may be anything from 32 to 95
     * blocks tall in the world, and picking the top of that range would produce a sliver of
     * pixels in the quest book, so the builders ask for something nearer the bottom.</p>
     */
    int number(String field, double bias, int fallback) {
        JsonElement element = json.get(field);
        if (element == null) {
            return fallback;
        }
        if (element.isJsonPrimitive()) {
            try {
                return element.getAsInt();
            } catch (RuntimeException e) {
                return fallback;
            }
        }
        if (!element.isJsonObject()) {
            return fallback;
        }
        JsonObject provider = element.getAsJsonObject();
        if (provider.has("value")) { // minecraft:constant
            return provider.get("value").getAsInt();
        }
        if (provider.has("min_inclusive") && provider.has("max_inclusive")) { // minecraft:uniform
            int min = provider.get("min_inclusive").getAsInt();
            int max = provider.get("max_inclusive").getAsInt();
            return min + (int) Math.round((max - min) * Math.clamp(bias, 0D, 1D));
        }
        return fallback;
    }

    /**
     * A block from one of the {@code BlockStateProvider} fields, falling back to the given id
     * when the field is absent, is a provider shape we do not read, or names a block no
     * installed mod has. A {@code weighted_state_provider} yields its first entry: the
     * builders want one representative block, not a sample.
     */
    @Nullable
    BlockState state(String field, String fallbackId) {
        BlockState state = readState(json.get(field));
        return state != null ? state : BlockStates.state(fallbackId);
    }

    @Nullable
    private static BlockState readState(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject provider = element.getAsJsonObject();
        JsonObject state = provider.getAsJsonObject("state");
        if (state == null && provider.has("entries")) {
            JsonElement first = provider.getAsJsonArray("entries").isEmpty() ? null
                    : provider.getAsJsonArray("entries").get(0);
            if (first != null && first.isJsonObject()) {
                state = first.getAsJsonObject().getAsJsonObject("data");
            }
        }
        String name = state == null ? null : string(state.get("Name"));
        if (name == null) {
            return null;
        }
        BlockState block = BlockStates.state(name);
        JsonObject properties = state.getAsJsonObject("Properties");
        if (block != null && properties != null) {
            for (Map.Entry<String, JsonElement> property : properties.entrySet()) {
                String value = string(property.getValue());
                if (value != null) {
                    block = BlockStates.with(block, property.getKey(), value);
                }
            }
        }
        return block;
    }

    @Nullable
    private static String string(@Nullable JsonElement element) {
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
