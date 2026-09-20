package net.gabriele333.gabrielequests.client.structure.composite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.gabriele333.gabrielequests.client.structure.StructureData;
import net.gabriele333.gabrielequests.client.structure.StructureLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The catalogue of {@linkplain CompositeRecipe composite structures} and the code that
 * builds one into renderable blocks.
 *
 * <p>Two sources, merged: a handful of <b>built-in</b> recipes for well-known structures
 * whose mod generates them in Java, and any JSON a pack drops in
 * {@code config/questsvisual/composites/<namespace>/<name>.json}, which becomes the
 * structure id {@code <namespace>:<name>}. Pack-provided files win over built-ins, so a
 * pack can retune one it does not like. The JSON mirrors the record:</p>
 *
 * <pre>
 * {
 *   "parts": [
 *     {"template": "twilightforest:lich_tower/tower_foyer"},
 *     {"template": "twilightforest:lich_tower/tower_slice", "repeat": 3},
 *     {"template": "twilightforest:lich_tower/tower_boss_room"},
 *     {"template": "some:piece", "offset": [4, 0, 12], "rotation": "clockwise_90"}
 *   ]
 * }
 * </pre>
 *
 * <p>A part with an {@code offset} is placed there; a part without one is stacked on top of
 * everything so far and centred, which is what makes a tower one line per floor.</p>
 *
 * <p>Because the folder is under {@code config}, it ships with the pack like any other
 * config - which is what makes a pack-authored composite render for every player, not just
 * for the author.</p>
 */
public final class CompositeStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");
    private static final String FOLDER = "questsvisual/composites";
    private static final String JSON = ".json";

    @Nullable
    private static Map<String, CompositeRecipe> recipes;
    /** Built results, keyed by (id, markers). */
    private static final Map<String, StructureData> CACHE = new HashMap<>();

    private CompositeStructures() {
    }

    /** Every composite id, sorted. */
    public static List<String> ids() {
        return List.copyOf(recipes().keySet());
    }

    public static boolean has(String id) {
        return recipes().containsKey(id);
    }

    /**
     * Builds the composite, or {@code null} when there is no such recipe, none of its
     * templates are installed, or the result blew the render budget.
     */
    @Nullable
    public static StructureData build(String id, boolean markers) {
        String key = id + "|" + markers;
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        StructureData built = null;
        try {
            CompositeRecipe recipe = recipes().get(id);
            if (recipe != null) {
                built = assemble(id, recipe, markers);
            }
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to build composite structure {}", id, t);
        }
        CACHE.put(key, built);
        return built;
    }

    /** Places every part, then shifts the result so it starts at the origin. */
    @Nullable
    private static StructureData assemble(String id, CompositeRecipe recipe, boolean markers) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        // Running footprint of what has been stacked so far, so the next part can centre on
        // it and land on top.
        int top = 0;
        int centerX = 0;
        int centerZ = 0;
        boolean anyStacked = false;

        for (CompositeRecipe.Part part : recipe.parts()) {
            StructureLoader.RawTemplate template = StructureLoader.readRaw(part.templateId());
            if (template == null) {
                // A missing piece costs its own part, not the whole structure: a pack may
                // ship a recipe for a mod that is only sometimes installed.
                continue;
            }
            BlockPos size = rotatedSize(template.size(), part.rotation());
            for (int copy = 0; copy < part.repeat(); copy++) {
                BlockPos offset;
                if (part.stack()) {
                    if (!anyStacked) {
                        centerX = size.getX() / 2;
                        centerZ = size.getZ() / 2;
                        anyStacked = true;
                    }
                    offset = new BlockPos(centerX - size.getX() / 2, top, centerZ - size.getZ() / 2);
                    top += size.getY();
                } else {
                    BlockPos base = part.offset() == null ? BlockPos.ZERO : part.offset();
                    offset = base.offset(part.step().multiply(copy));
                }
                paste(template, part.rotation(), offset, markers, blocks, blockEntities);
            }
        }
        if (blocks.isEmpty()) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Composite {} produced no blocks (its mod is probably absent)", id);
            return null;
        }
        return normalize(id, blocks, blockEntities);
    }

    /** Copies one template's blocks into the accumulating maps, rotated and offset. */
    private static void paste(StructureLoader.RawTemplate template, Rotation rotation, BlockPos offset,
                              boolean markers, Map<BlockPos, BlockState> blocks,
                              Map<BlockPos, CompoundTag> blockEntities) {
        BlockPos size = template.size();
        for (StructureLoader.RawBlock block : template.blocks()) {
            if (StructureLoader.isHidden(block.state(), markers)) {
                continue;
            }
            BlockPos pos = rotate(block.pos(), rotation, size).offset(offset);
            blocks.put(pos, block.state().rotate(rotation));
            if (block.nbt() != null) {
                blockEntities.put(pos, block.nbt());
            } else {
                blockEntities.remove(pos);
            }
        }
    }

    /** Shifts everything so the minimum corner is the origin, as the renderer expects. */
    @Nullable
    private static StructureData normalize(String id, Map<BlockPos, BlockState> blocks,
                                           Map<BlockPos, CompoundTag> blockEntities) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        BlockPos min = new BlockPos(minX, minY, minZ);
        BlockPos size = new BlockPos(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        if (!StructureLoader.withinBudget(id, size) || blocks.size() > StructureLoader.MAX_BLOCKS) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Composite {} is {} blocks in a {}x{}x{} box; too big to render",
                    id, blocks.size(), size.getX(), size.getY(), size.getZ());
            return null;
        }
        Map<BlockPos, BlockState> shifted = new HashMap<>(blocks.size());
        blocks.forEach((pos, state) -> shifted.put(pos.subtract(min), state));
        Map<BlockPos, CompoundTag> shiftedEntities = new HashMap<>(blockEntities.size());
        blockEntities.forEach((pos, tag) -> shiftedEntities.put(pos.subtract(min), tag));
        LOGGER.info("[GabrieleQuests/QuestsTools] Built composite {} ({} blocks, {}x{}x{})",
                id, shifted.size(), size.getX(), size.getY(), size.getZ());
        return StructureData.of(shifted, shiftedEntities, size);
    }

    /** Rotation about the template origin, with the shift that keeps coordinates positive. */
    private static BlockPos rotate(BlockPos pos, Rotation rotation, BlockPos size) {
        BlockPos rotated = StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO);
        return switch (rotation) {
            case NONE -> rotated;
            case CLOCKWISE_90 -> rotated.offset(size.getZ() - 1, 0, 0);
            case CLOCKWISE_180 -> rotated.offset(size.getX() - 1, 0, size.getZ() - 1);
            case COUNTERCLOCKWISE_90 -> rotated.offset(0, 0, size.getX() - 1);
        };
    }

    private static BlockPos rotatedSize(BlockPos size, Rotation rotation) {
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                ? new BlockPos(size.getZ(), size.getY(), size.getX())
                : size;
    }

    private static synchronized Map<String, CompositeRecipe> recipes() {
        if (recipes == null) {
            Map<String, CompositeRecipe> map = new LinkedHashMap<>(BuiltInComposites.all());
            map.putAll(loadFromConfig()); // pack files win
            recipes = map;
            LOGGER.info("[GabrieleQuests/QuestsTools] {} composite structure recipes available", map.size());
        }
        return recipes;
    }

    /** Reads {@code config/questsvisual/composites/<namespace>/<name>.json}. */
    private static Map<String, CompositeRecipe> loadFromConfig() {
        Map<String, CompositeRecipe> map = new LinkedHashMap<>();
        Path root = FMLPaths.CONFIGDIR.get().resolve(FOLDER);
        if (!Files.isDirectory(root)) {
            return map;
        }
        try (Stream<Path> namespaces = Files.list(root)) {
            for (Path namespaceDir : namespaces.toList()) {
                if (!Files.isDirectory(namespaceDir)) {
                    continue;
                }
                String namespace = String.valueOf(namespaceDir.getFileName());
                try (Stream<Path> files = Files.walk(namespaceDir)) {
                    for (Path file : files.toList()) {
                        String name = String.valueOf(file.getFileName());
                        if (!name.endsWith(JSON) || !Files.isRegularFile(file)) {
                            continue;
                        }
                        String relative = namespaceDir.relativize(file).toString().replace('\\', '/');
                        String id = namespace + ":" + relative.substring(0, relative.length() - JSON.length());
                        CompositeRecipe recipe = parse(file);
                        if (recipe != null) {
                            map.put(id, recipe);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not read composite structures from {}", root, t);
        }
        return map;
    }

    @Nullable
    private static CompositeRecipe parse(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonArray parts = parsed.getAsJsonObject().getAsJsonArray("parts");
            if (parts == null) {
                return null;
            }
            CompositeRecipe.Builder builder = CompositeRecipe.builder();
            for (JsonElement raw : parts) {
                if (!raw.isJsonObject()) {
                    continue;
                }
                JsonObject part = raw.getAsJsonObject();
                String template = part.has("template") ? part.get("template").getAsString() : null;
                if (template == null) {
                    continue;
                }
                Rotation rotation = readRotation(part.get("rotation"));
                int repeat = part.has("repeat") ? part.get("repeat").getAsInt() : 1;
                JsonArray offset = part.getAsJsonArray("offset");
                if (offset != null && offset.size() == 3) {
                    JsonArray step = part.getAsJsonArray("step");
                    if (step != null && step.size() == 3) {
                        builder.repeat(template, offset.get(0).getAsInt(), offset.get(1).getAsInt(),
                                offset.get(2).getAsInt(), repeat,
                                step.get(0).getAsInt(), step.get(1).getAsInt(), step.get(2).getAsInt());
                    } else {
                        builder.at(template, offset.get(0).getAsInt(), offset.get(1).getAsInt(),
                                offset.get(2).getAsInt(), rotation);
                    }
                } else {
                    builder.stack(template, repeat);
                }
            }
            return builder.build();
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not parse composite structure {}", file, t);
            return null;
        }
    }

    private static Rotation readRotation(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return Rotation.NONE;
        }
        try {
            return Rotation.valueOf(element.getAsString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Rotation.NONE;
        }
    }
}
