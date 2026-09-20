package net.gabriele333.gabrielequests.dev;

import net.gabriele333.gabrielequests.client.structure.StructureLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development tool: runs a mod's real structure generator once and saves what it built as a
 * {@code .nbt} template, so structures that only exist as world-generation code can be shipped
 * as data and shown in a quest book.
 *
 * <p><b>Why baking rather than porting.</b> Twilight Forest builds seventeen of its twenty-one
 * structures purely in Java - a dark tower is dozens of component classes deciding floor by
 * floor what to place. Re-implementing each one is weeks of work per dungeon and drifts the
 * moment the mod changes. Running the generator once and keeping the result is the whole
 * structure, exactly as the mod builds it, at the cost of it being <em>one</em> instance
 * rather than a fresh roll each time - which for a quest-book illustration is not a cost at
 * all: every player seeing the same dark tower is the point.</p>
 *
 * <p><b>Why it cannot run on a player's client.</b> The structure registry is a data-pack
 * registry and is never synced to clients, so a client on a server has no {@link Structure}
 * to generate from - and generation also wants a chunk generator, a biome source and a random
 * state, which only the logical server has. That is what forces the work to happen here, once,
 * against a dev world, with the answer committed as data.</p>
 *
 * <p><b>How it works.</b> For each structure: generate a {@link StructureStart} at a fixed
 * seed and a fixed chunk far from spawn (biome checks bypassed, since the point is to build
 * the thing, not to find where it would legally go), <b>clear its bounding box to air</b>,
 * place the pieces, then read back everything that is not air. Clearing first is what makes
 * the read-back exact: a diff against the previous terrain would silently lose every block the
 * structure places that happens to match the stone it replaced.</p>
 *
 * <p><b>What it cannot capture.</b> Structures that <em>carve</em> rather than build - troll
 * caves, yeti caves, hollow hills - place almost nothing; their walls are the terrain they were
 * hollowed out of, and terrain is not part of any structure. Those stay with the procedural
 * builders in {@code client/structure/twilight}.</p>
 *
 * <p>Off unless asked for. Set {@code GABRIELEQUESTS_BAKE_STRUCTURES} to a comma-separated
 * list of structure ids (or {@code twilightforest} for all of that mod's), and run the dev
 * client on a world: set {@code GABRIELEQUESTS_BAKE_WORLD} to a folder in {@code run/saves} and
 * the client opens straight into it, because an integrated server is required.</p>
 */
public final class StructureBaker {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    private static final String STRUCTURES = "GABRIELEQUESTS_BAKE_STRUCTURES";
    private static final String OUTPUT = "GABRIELEQUESTS_BAKE_OUTPUT";

    /**
     * The seed every baked structure is generated with. Fixed once, on purpose and forever:
     * changing it would re-roll every structure this tool has ever produced, and the whole
     * value of baking is that the picture stays the one the quest book was written around.
     */
    private static final long SEED = 20260812L;

    /** Where to bake, in chunks: far from spawn, and far apart so two never share a chunk. */
    private static final int ORIGIN_CHUNK = 30_000;
    private static final int STRIDE_CHUNKS = 64;

    /** Structures bigger than the renderer can draw are not worth writing to disk. */
    private static final int MAX_SPAN = StructureLoader.MAX_SIZE;

    private StructureBaker() {
    }

    /** The ids to bake, or an empty list when the tool has not been asked for. */
    public static List<String> requested() {
        String value = System.getenv(STRUCTURES);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","));
    }

    public static void run(MinecraftServer server) {
        Path output = Path.of(System.getenv(OUTPUT) != null ? System.getenv(OUTPUT)
                : "../src/main/resources/data/gabrielequests/structure");
        ServerLevel level = server.overworld();
        RegistryAccess access = server.registryAccess();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        StructureTemplateManager templates = server.getStructureManager();

        List<ResourceLocation> ids = resolve(access, requested());
        LOGGER.info("[GabrieleQuests/QuestsTools] ---- baking {} structures at seed {} ----", ids.size(), SEED);
        int slot = 0;
        for (ResourceLocation id : ids) {
            try {
                bake(level, access, generator, randomState, templates, id, slot++, output);
            } catch (Throwable t) {
                LOGGER.error("[GabrieleQuests/QuestsTools] BAKE {} threw", id, t);
            }
        }
        LOGGER.info("[GabrieleQuests/QuestsTools] ---- baking done ----");
    }

    /** Expands a bare namespace into every structure it registers; leaves ids alone. */
    private static List<ResourceLocation> resolve(RegistryAccess access, List<String> requested) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (String entry : requested) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.indexOf(':') < 0) {
                access.registryOrThrow(Registries.STRUCTURE).keySet().stream()
                        .filter(key -> key.getNamespace().equals(trimmed))
                        .sorted()
                        .forEach(ids::add);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(trimmed);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private static void bake(ServerLevel level, RegistryAccess access, ChunkGenerator generator,
                             RandomState randomState, StructureTemplateManager templates,
                             ResourceLocation id, int slot, Path output) throws Exception {
        Structure structure = access.registryOrThrow(Registries.STRUCTURE).get(id);
        if (structure == null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] BAKE {} -> not registered", id);
            return;
        }
        ChunkPos chunkPos = new ChunkPos(ORIGIN_CHUNK + slot * STRIDE_CHUNKS, ORIGIN_CHUNK);
        // validBiome is answered "yes" for everything: we are building the structure, not
        // deciding where world generation would be allowed to put it.
        StructureStart start = structure.generate(access, generator, generator.getBiomeSource(),
                randomState, templates, SEED, chunkPos, 0, level, biome -> true);
        if (!start.isValid()) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] BAKE {} -> the generator produced no pieces here", id);
            return;
        }
        BoundingBox box = start.getBoundingBox();
        if (box.getXSpan() > MAX_SPAN || box.getYSpan() > MAX_SPAN || box.getZSpan() > MAX_SPAN) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] BAKE {} -> {}x{}x{}, past the {} render limit; skipped",
                    id, box.getXSpan(), box.getYSpan(), box.getZSpan(), MAX_SPAN);
            return;
        }
        BoundingBox clamped = new BoundingBox(box.minX(), Math.max(box.minY(), level.getMinBuildHeight()),
                box.minZ(), box.maxX(), Math.min(box.maxY(), level.getMaxBuildHeight() - 1), box.maxZ());

        long started = System.currentTimeMillis();
        clear(level, clamped);
        place(level, generator, start, clamped);
        Map<BlockPos, BlockState> blocks = read(level, clamped);
        if (blocks.isEmpty()) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] BAKE {} -> placed no blocks (it carves terrain rather "
                    + "than building; use a procedural builder for this one)", id);
            return;
        }
        Path file = output.resolve(id.getNamespace()).resolve(id.getPath() + ".nbt");
        write(level, blocks, clamped, file);
        LOGGER.info("[GabrieleQuests/QuestsTools] BAKE {} -> {} blocks, {}x{}x{}, {} KB, {} ms", id,
                blocks.size(), clamped.getXSpan(), clamped.getYSpan(), clamped.getZSpan(),
                Files.size(file) / 1024, System.currentTimeMillis() - started);
    }

    /**
     * Empties the box. {@code UPDATE_NONE} on purpose: neighbour updates over half a million
     * positions would cost far more than the clearing itself, and nothing here is ever meant
     * to behave like a real build.
     */
    private static void clear(ServerLevel level, BoundingBox box) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, air, Block.UPDATE_NONE);
                    }
                }
            }
        }
    }

    /** Places the pieces the way world generation would: one chunk at a time. */
    private static void place(ServerLevel level, ChunkGenerator generator, StructureStart start,
                              BoundingBox box) {
        RandomSource random = RandomSource.create(SEED);
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                BoundingBox chunkBox = new BoundingBox(chunkPos.getMinBlockX(), box.minY(),
                        chunkPos.getMinBlockZ(), chunkPos.getMaxBlockX(), box.maxY(), chunkPos.getMaxBlockZ());
                start.placeInChunk(level, level.structureManager(), generator, random, chunkBox, chunkPos);
            }
        }
    }

    /** Everything left standing in the box, keyed by position relative to its corner. */
    private static Map<BlockPos, BlockState> read(ServerLevel level, BoundingBox box) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    pos.set(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        blocks.put(new BlockPos(x - box.minX(), y - box.minY(), z - box.minZ()), state);
                    }
                }
            }
        }
        return blocks;
    }

    /**
     * Writes a vanilla structure template. Deliberately the stock format rather than one of
     * our own: {@code StructureLoader} already reads it, so a baked structure needs no new
     * decoding path and stays inspectable with a structure block.
     */
    private static void write(ServerLevel level, Map<BlockPos, BlockState> blocks, BoundingBox box,
                              Path file) throws Exception {
        Map<BlockState, Integer> palette = new HashMap<>();
        ListTag paletteTag = new ListTag();
        ListTag blockList = new ListTag();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockState state = entry.getValue();
            Integer index = palette.get(state);
            if (index == null) {
                index = palette.size();
                palette.put(state, index);
                paletteTag.add(NbtUtils.writeBlockState(state));
            }
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(entry.getKey().getX(), entry.getKey().getY(), entry.getKey().getZ()));
            block.putInt("state", index);
            CompoundTag blockEntity = blockEntity(level, box, entry.getKey());
            if (blockEntity != null) {
                block.put("nbt", blockEntity);
            }
            blockList.add(block);
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.put("size", ints(box.getXSpan(), box.getYSpan(), box.getZSpan()));
        tag.put("palette", paletteTag);
        tag.put("blocks", blockList);
        tag.put("entities", new ListTag());

        Files.createDirectories(file.getParent());
        try (OutputStream stream = Files.newOutputStream(file)) {
            NbtIo.writeCompressed(tag, stream);
        }
    }

    /** The stored contents of a chest, sign or banner, so it renders with them. */
    @Nullable
    private static CompoundTag blockEntity(ServerLevel level, BoundingBox box, BlockPos relative) {
        BlockEntity entity = level.getBlockEntity(new BlockPos(relative.getX() + box.minX(),
                relative.getY() + box.minY(), relative.getZ() + box.minZ()));
        return entity == null ? null : entity.saveWithId(level.registryAccess());
    }

    private static ListTag ints(int x, int y, int z) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(x));
        list.add(IntTag.valueOf(y));
        list.add(IntTag.valueOf(z));
        return list;
    }
}
