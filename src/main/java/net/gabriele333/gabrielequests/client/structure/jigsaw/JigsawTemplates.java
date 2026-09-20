package net.gabriele333.gabrielequests.client.structure.jigsaw;

import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import net.gabriele333.gabrielequests.client.structure.StructureLoader;
import net.minecraft.world.level.block.JigsawBlock;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "no template pools" half of the assembler: an index over the templates filed under a
 * structure's own path, built by reading which jigsaw <em>names</em> each one exposes.
 *
 * <p>Vanilla structures say which templates may be drawn through
 * {@code worldgen/template_pool} JSONs, and when those exist {@link JigsawAssembler} uses
 * them. Some mods instead drive the very same jigsaw blocks from their own Java: Twilight
 * Forest's Lich Tower and camps are wired by {@code TwilightJigsawPiece}, so the templates
 * carry proper {@code name}/{@code target} pairs but there is not a single pool to read.
 * This class recovers the missing half from the templates themselves - if a jigsaw wants a
 * {@code target}, the candidates are simply the templates that expose a jigsaw with that
 * {@code name}.</p>
 *
 * <p>The index is scoped to one structure (templates whose id starts with the structure's
 * own path, e.g. {@code twilightforest:lich_tower/...} for
 * {@code twilightforest:lich_tower}). That keeps it small, keeps unrelated structures from
 * bleeding into each other, and matches how mods file their templates.</p>
 *
 * <p>Building it means decoding every template of that structure once, so it is cached per
 * structure and only ever built for structures that actually need it.</p>
 */
final class JigsawTemplates {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** The convention mods use to mark the piece a structure starts from. */
    private static final String START_MARKER = "structure_start";

    /** Structures whose templates are too many to index without a noticeable stall. */
    private static final int MAX_TEMPLATES = 256;

    /** structure id -> (jigsaw name -> templates exposing it). */
    private static final Map<String, Map<String, List<String>>> INDEXES = new HashMap<>();
    /** structure id -> its start template, or absent when it has none. */
    private static final Map<String, String> STARTS = new HashMap<>();

    private JigsawTemplates() {
    }

    /**
     * The template a structure starts from, found by the {@code structure_start} marker, or
     * {@code null} when this structure has no templates of its own (a mod that generates
     * everything in Java places no {@code .nbt} at all - nothing here can help those).
     */
    @Nullable
    static synchronized String findStart(String structureId) {
        if (STARTS.containsKey(structureId)) {
            return STARTS.get(structureId);
        }
        String start = null;
        try {
            String largest = null;
            int largestBlocks = 0;
            for (String templateId : templatesOf(structureId)) {
                StructureLoader.RawTemplate template = StructureLoader.readRaw(templateId);
                if (template == null) {
                    continue;
                }
                boolean hasJigsaw = false;
                for (StructureLoader.RawBlock block : template.blocks()) {
                    if (block.state().getBlock() instanceof JigsawBlock && block.nbt() != null) {
                        hasJigsaw = true;
                        if (block.nbt().getString("target").contains(START_MARKER)) {
                            start = templateId;
                            break;
                        }
                    }
                }
                if (start != null) {
                    break;
                }
                if (hasJigsaw && template.blocks().size() > largestBlocks) {
                    largestBlocks = template.blocks().size();
                    largest = templateId;
                }
            }
            if (start == null) {
                // No explicit marker. The biggest piece that has jigsaws at all is the best
                // guess at a root: structures are built outward from their main hall, not
                // from a doorway.
                start = largest;
            }
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not look for a start piece of {}", structureId, t);
        }
        STARTS.put(structureId, start);
        return start;
    }

    /** Templates exposing a jigsaw block named {@code name}, within this structure. */
    static synchronized List<String> candidatesFor(String structureId, String name) {
        return index(structureId).getOrDefault(name, List.of());
    }

    private static Map<String, List<String>> index(String structureId) {
        Map<String, List<String>> cached = INDEXES.get(structureId);
        if (cached != null) {
            return cached;
        }
        Map<String, List<String>> index = new HashMap<>();
        try {
            for (String templateId : templatesOf(structureId)) {
                StructureLoader.RawTemplate template = StructureLoader.readRaw(templateId);
                if (template == null) {
                    continue;
                }
                Set<String> names = new LinkedHashSet<>();
                for (StructureLoader.RawBlock block : template.blocks()) {
                    if (block.state().getBlock() instanceof JigsawBlock && block.nbt() != null) {
                        names.add(block.nbt().getString("name"));
                    }
                }
                for (String name : names) {
                    index.computeIfAbsent(name, key -> new ArrayList<>()).add(templateId);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not index the templates of {}", structureId, t);
        }
        LOGGER.info("[GabrieleQuests/QuestsTools] Indexed {} jigsaw names for {}", index.size(), structureId);
        INDEXES.put(structureId, index);
        return index;
    }

    /**
     * The templates belonging to a structure: those filed under its own path. For
     * {@code twilightforest:lich_tower} that is every {@code twilightforest:lich_tower/...}
     * template.
     */
    private static List<String> templatesOf(String structureId) {
        String prefix = structureId + "/";
        List<String> templates = StructureIndex.ids().stream()
                .filter(id -> id.startsWith(prefix))
                .limit(MAX_TEMPLATES)
                .toList();
        return templates;
    }
}
