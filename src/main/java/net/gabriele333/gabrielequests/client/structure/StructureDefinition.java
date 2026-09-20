package net.gabriele333.gabrielequests.client.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.gabriele333.gabrielequests.client.render.BlockStates;
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
 * A {@code worldgen/structure/<id>.json} as the non-jigsaw integrations read it: the
 * {@code type} they dispatch on, plus whatever numbers and blocks that type is tuned with.
 *
 * <p>Read from a mod's own data rather than copied into constants here, for the same reason the
 * Twilight Forest builders do it: a version that retunes the bronze dungeon's room count, or a
 * pack that ships a second aercloud structure out of a different cloud block, then comes out
 * right with nothing here changing. Blocks go through {@link BlockStates} - <b>ids only</b> -
 * so no integration built on this is a compile-time or hard runtime dependency on its mod.</p>
 *
 * <p>Plain Gson, like {@code JigsawDefinitions}: the vanilla codecs want registries that only
 * the logical server has, and this has to work on any client.</p>
 */
public final class StructureDefinition {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Parsed definitions by structure id; a {@code null} value memoises "not readable". */
    private static final Map<String, StructureDefinition> CACHE = new HashMap<>();

    private final String type;
    private final JsonObject json;

    private StructureDefinition(String type, JsonObject json) {
        this.type = type;
        this.json = json;
    }

    /** The definition of a structure, or {@code null} when there is no readable JSON for it. */
    @Nullable
    public static synchronized StructureDefinition of(String structureId) {
        if (CACHE.containsKey(structureId)) {
            return CACHE.get(structureId);
        }
        StructureDefinition parsed = null;
        try {
            Path path = StructureIndex.structurePath(structureId);
            if (path != null && Files.isRegularFile(path)) {
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (element.isJsonObject()) {
                        JsonObject object = element.getAsJsonObject();
                        JsonElement type = object.get("type");
                        if (type != null && type.isJsonPrimitive()) {
                            parsed = new StructureDefinition(type.getAsString(), object);
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

    /** The structure type a definition declares, or {@code ""} when there is no definition. */
    public static String typeOf(String structureId) {
        StructureDefinition definition = of(structureId);
        return definition == null ? "" : definition.type();
    }

    public String type() {
        return type;
    }

    /** A plain integer field, or {@code fallback} when it is missing or not a number. */
    public int number(String field, int fallback) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * The block state of a {@code minecraft:simple_state_provider} field, or {@code null} when
     * the field is missing, is one of the randomised providers (nothing sensible to pick for a
     * fixed picture) or names a block no installed mod provides.
     */
    @Nullable
    public BlockState state(String field) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonElement state = element.getAsJsonObject().get("state");
        if (state == null || !state.isJsonObject()) {
            return null;
        }
        JsonObject object = state.getAsJsonObject();
        JsonElement name = object.get("Name");
        if (name == null || !name.isJsonPrimitive()) {
            return null;
        }
        BlockState result = BlockStates.state(name.getAsString());
        JsonElement properties = object.get("Properties");
        if (result != null && properties != null && properties.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : properties.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    result = BlockStates.with(result, entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        return result;
    }
}
