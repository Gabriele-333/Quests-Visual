package net.gabriele333.gabrielequests.client.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a {@code .nbt} structure template. This is the same format and the same decoding
 * path vanilla uses ({@code StructureTemplateManager}): read the gzipped compound, run it
 * through the {@code STRUCTURE} data fixer so templates saved by older Minecraft versions
 * still resolve, then map the palette to block states and the block list to positions.
 *
 * <p>We parse the tag ourselves instead of going through {@code StructureTemplate} because
 * the template keeps its palettes private and offers no way to read every block back out -
 * and because the jigsaw assembler needs the raw block list, jigsaw blocks included, which
 * the vanilla class will not hand over either.</p>
 *
 * <p>Two levels: {@link #readRaw} gives everything the file contains (cached, shared with
 * {@link net.gabriele333.gabrielequests.client.structure.jigsaw.JigsawAssembler}), and
 * {@link #load} is the "one template on its own" view used when the author picked a single
 * piece. Nothing here throws: a missing, truncated or foreign file yields {@code null} and
 * the icon draws a placeholder.</p>
 */
public final class StructureLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /**
     * Budget for one rendered structure: a guard against a pathological template - or an
     * assembly that ran away - freezing the client while it tesselates, not a judgement about
     * what is worth showing.
     *
     * <p>Both were raised for the baked dungeons, which are the real thing rather than a
     * sketch of it and are correspondingly large: Twilight Forest's dark tower is 211 blocks
     * tall and its knight stronghold 204 wide, so a 192 ceiling threw away two of the
     * structures the feature exists for, and the aurora palace's 145k blocks came within 4k of
     * the old block cap. Past roughly 200 blocks a side an icon is showing under 5 px per
     * block anyway, so 256 is about where a bigger number stops buying a better picture.</p>
     */
    public static final int MAX_SIZE = 256;
    public static final int MAX_BLOCKS = 250_000;

    /** One block of a template, exactly as the file stores it. */
    public record RawBlock(BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
    }

    /** A decoded template: its declared size and every block, jigsaw markers included. */
    public record RawTemplate(BlockPos size, List<RawBlock> blocks) {
    }

    /**
     * Decoded templates. The assembler touches dozens of them per structure, so this is
     * sized for a whole assembly rather than for a couple of previews.
     */
    private static final int MAX_RAW_CACHED = 96;
    private static final LinkedHashMap<String, RawTemplate> RAW_CACHE =
            new LinkedHashMap<>(128, 0.75F, true);
    /** Ids that failed once; never retried (and never logged twice). */
    private static final Set<String> FAILED = new HashSet<>();

    /** Assembled piece views, keyed by (id, markers). */
    private static final int MAX_CACHED = 6;
    private static final LinkedHashMap<String, StructureData> CACHE =
            new LinkedHashMap<>(8, 0.75F, true);

    /**
     * Blocks that only exist to drive world generation. They are invisible or intrusive in
     * a showcase, so they are hidden unless the author asks for them - except
     * {@code structure_void}, which means "leave whatever is here" and is always dropped.
     */
    private static final Set<String> MARKERS = Set.of(
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:barrier",
            "minecraft:light");
    private static final String STRUCTURE_VOID = "minecraft:structure_void";

    private StructureLoader() {
    }

    /**
     * The whole template as stored, or {@code null} when it is not installed or unreadable.
     * Callers must not mutate the returned lists - the result is shared through the cache.
     */
    @Nullable
    public static RawTemplate readRaw(String id) {
        RawTemplate cached = RAW_CACHE.get(id);
        if (cached != null || FAILED.contains(id)) {
            return cached;
        }
        RawTemplate template = null;
        try {
            template = read(id);
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to read structure {}", id, t);
        }
        if (template == null) {
            FAILED.add(id);
            return null;
        }
        RAW_CACHE.put(id, template);
        while (RAW_CACHE.size() > MAX_RAW_CACHED) {
            RAW_CACHE.remove(RAW_CACHE.entrySet().iterator().next().getKey());
        }
        return template;
    }

    /**
     * A single template rendered on its own, or {@code null} when it is unavailable or over
     * budget.
     *
     * @param markers keep world-gen marker blocks (structure/jigsaw blocks, barriers, light)
     */
    @Nullable
    public static StructureData load(String id, boolean markers) {
        String key = id + "|" + markers;
        StructureData cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        RawTemplate template = readRaw(id);
        if (template == null) {
            return null;
        }
        BlockPos size = template.size();
        if (!withinBudget(id, size)) {
            return null;
        }
        // Pre-sized: a baked dungeon is six figures of blocks, and letting the map grow into
        // that from 16 buckets is a dozen rehashes of everything placed so far.
        Map<BlockPos, BlockState> blocks = HashMap.newHashMap(template.blocks().size());
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (RawBlock block : template.blocks()) {
            if (isHidden(block.state(), markers)) {
                continue;
            }
            blocks.put(block.pos(), block.state());
            if (block.nbt() != null) {
                blockEntities.put(block.pos(), block.nbt());
            }
        }
        if (blocks.isEmpty()) {
            // Everything in this template is a world-gen marker: a spawn point, a jigsaw
            // anchor, a barrier cage. Vanilla ships a fair number of those
            // (village/desert/camel_spawn is one block, a jigsaw), and drawing nothing at all
            // is indistinguishable from "this mod is missing" - the author picks it and gets
            // an empty frame. Showing the markers is honest and, since the option exists
            // anyway, it is exactly what the author would have had to switch on by hand.
            if (!markers) {
                StructureData withMarkers = load(id, true);
                if (withMarkers != null) {
                    LOGGER.info("[GabrieleQuests/QuestsTools] Structure {} is world-gen markers only; "
                            + "drawing them rather than nothing", id);
                    return cache(key, withMarkers);
                }
            }
            LOGGER.warn("[GabrieleQuests/QuestsTools] Structure {} decoded to no visible blocks", id);
            return null;
        }
        return cache(key, StructureData.of(blocks, blockEntities, size));
    }

    private static StructureData cache(String key, StructureData data) {
        CACHE.put(key, data);
        while (CACHE.size() > MAX_CACHED) {
            CACHE.remove(CACHE.entrySet().iterator().next().getKey());
        }
        return data;
    }

    /** True when a bounding box is small enough to render; logs and fails it otherwise. */
    public static boolean withinBudget(String id, BlockPos size) {
        return withinBudget(id, size, true);
    }

    /**
     * As above, with the log suppressed for callers that <em>expect</em> to overshoot and
     * recover (the assembler trims a piece and asks again, which would otherwise log the same
     * warning once per attempt).
     */
    public static boolean withinBudget(String id, BlockPos size, boolean log) {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            if (log) {
                LOGGER.warn("[GabrieleQuests/QuestsTools] Structure {} has an empty bounding box", id);
            }
            return false;
        }
        if (size.getX() > MAX_SIZE || size.getY() > MAX_SIZE || size.getZ() > MAX_SIZE) {
            if (log) {
                LOGGER.warn("[GabrieleQuests/QuestsTools] Structure {} is {}x{}x{}, outside the {} block limit",
                        id, size.getX(), size.getY(), size.getZ(), MAX_SIZE);
            }
            return false;
        }
        return true;
    }

    /** True for blocks a display should not show (always air/structure void, optionally markers). */
    public static boolean isHidden(BlockState state, boolean markers) {
        if (state.isAir()) {
            return true;
        }
        ResourceLocation block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String id = block.toString();
        return STRUCTURE_VOID.equals(id) || (!markers && MARKERS.contains(id));
    }

    @Nullable
    private static RawTemplate read(String id) throws Exception {
        Path file = StructureIndex.path(id);
        if (file == null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] No installed mod provides the structure {}", id);
            return null;
        }
        CompoundTag tag;
        try (InputStream stream = Files.newInputStream(file)) {
            tag = NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        }
        // Same fallback version vanilla uses for templates written before DataVersion existed.
        int version = NbtUtils.getDataVersion(tag, 500);
        tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, version);

        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        if (sizeTag.size() != 3) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Structure {} has no usable size tag", id);
            return null;
        }
        BlockPos size = new BlockPos(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2));

        BlockState[] palette = readPalette(tag);
        ListTag blockList = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<RawBlock> blocks = new ArrayList<>(blockList.size());
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            int state = entry.getInt("state");
            if (state < 0 || state >= palette.length) {
                continue;
            }
            ListTag posTag = entry.getList("pos", Tag.TAG_INT);
            if (posTag.size() != 3) {
                continue;
            }
            blocks.add(new RawBlock(
                    new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)),
                    palette[state],
                    entry.contains("nbt", Tag.TAG_COMPOUND) ? entry.getCompound("nbt") : null));
        }
        return new RawTemplate(size, List.copyOf(blocks));
    }

    /**
     * The block-state palette. A template may carry several alternative palettes (a
     * {@code palettes} list, used for randomised variants); we take the first, which is the
     * variant vanilla uses when the placement settings pick no other.
     */
    private static BlockState[] readPalette(CompoundTag tag) {
        ListTag paletteTag;
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
            paletteTag = palettes.isEmpty() ? new ListTag() : palettes.getList(0);
        } else {
            paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        }
        HolderGetter<Block> blockGetter = BuiltInRegistries.BLOCK.asLookup();
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            // readBlockState already degrades an unknown block (a mod that is not installed)
            // to air rather than throwing, which is exactly the behaviour we want.
            palette[i] = NbtUtils.readBlockState(blockGetter, paletteTag.getCompound(i));
        }
        return palette;
    }
}
