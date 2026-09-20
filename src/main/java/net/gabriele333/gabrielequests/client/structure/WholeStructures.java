package net.gabriele333.gabrielequests.client.structure;

import net.gabriele333.gabrielequests.client.structure.aether.AetherStructures;
import net.gabriele333.gabrielequests.client.structure.composite.CompositeStructures;
import net.gabriele333.gabrielequests.client.structure.gaia.GaiaStructures;
import net.gabriele333.gabrielequests.client.structure.jigsaw.JigsawAssembler;
import net.gabriele333.gabrielequests.client.structure.jigsaw.JigsawDefinitions;
import net.gabriele333.gabrielequests.client.structure.twilight.TwilightStructures;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The "complete structure" half of the catalogue: everything that can be shown as a whole
 * building rather than as one of the rooms it is made of.
 *
 * <p>Six ways a structure gets here, tried in this order:</p>
 * <ol>
 *   <li>a {@linkplain CompositeStructures composite recipe} - a hand-written or
 *       pack-supplied part list, arranging {@code .nbt} templates a mod ships but never
 *       wires together;</li>
 *   <li>a {@linkplain BakedStructures baked instance} - the structure as its own generator
 *       actually built it, run once in a dev world and shipped as a template. Nothing else
 *       gets a dungeon whose every room is decided by Java at generation time;</li>
 *   <li>a {@linkplain TwilightStructures Twilight Forest builder} - a port of that mod's own
 *       generator, for the shapes a baked instance cannot capture (the ones carved out of
 *       terrain rather than built);</li>
 *   <li>an {@linkplain net.gabriele333.gabrielequests.client.structure.aether.AetherStructures
 *       Aether builder} - the same idea for a mod that stitches shipped templates together in
 *       Java, so there are neither pools to solve nor jigsaw blocks to follow;</li>
 *   <li>a {@linkplain GaiaStructures Gaia Dimension builder} - the same case again, its two
 *       towers stacked the way that mod's own piece classes stack them;</li>
 *   <li>a {@code minecraft:jigsaw} structure definition, which
 *       {@link JigsawAssembler} solves straight from the mod's own template pools (vanilla
 *       villages, ancient cities, bastions, trail ruins, and every structure mod that uses
 *       the same system).</li>
 * </ol>
 *
 * <p>The order is "most faithful first, most general last". Composites win outright so a pack
 * can override anything below them by dropping a JSON next to it; a baked instance beats a
 * port of the generator because it <em>is</em> the generator's output; and both beat the
 * assembler, so a structure with a handful of incidental templates is not shown as that
 * handful.</p>
 */
public final class WholeStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    @Nullable
    private static List<String> ids;

    private WholeStructures() {
    }

    /** Every structure that can be shown whole, sorted. */
    public static synchronized List<String> ids() {
        if (ids == null) {
            List<String> found = new ArrayList<>(CompositeStructures.ids());
            for (String id : BakedStructures.ids()) {
                if (!found.contains(id)) {
                    found.add(id);
                }
            }
            for (String id : TwilightStructures.ids()) {
                if (!found.contains(id)) {
                    found.add(id);
                }
            }
            for (String id : AetherStructures.ids()) {
                if (!found.contains(id)) {
                    found.add(id);
                }
            }
            for (String id : GaiaStructures.ids()) {
                if (!found.contains(id)) {
                    found.add(id);
                }
            }
            for (String id : StructureIndex.structureIds()) {
                // Skip the ones already covered, and the ones that are not jigsaw-based (a
                // mod's Java generator - nothing to assemble).
                if (!found.contains(id) && JigsawDefinitions.isAssemblable(id)) {
                    found.add(id);
                }
            }
            found.sort(null);
            ids = List.copyOf(found);
        }
        return ids;
    }

    /** The whole structures of one namespace only. */
    public static List<String> ids(String namespace) {
        String prefix = namespace + ":";
        return ids().stream().filter(id -> id.startsWith(prefix)).toList();
    }

    /** Every namespace that offers at least one whole structure. */
    public static List<String> namespaces() {
        return ids().stream().map(StructureIndex::namespaceOf).distinct().toList();
    }

    public static boolean has(String id) {
        return ids().contains(id);
    }

    /**
     * Builds the whole structure, or {@code null} when it cannot be produced (its mod is
     * absent, its pools are missing, or the result is over the render budget).
     */
    @Nullable
    public static StructureData build(String id, boolean markers) {
        StructureData built = buildWhole(id, markers);
        return built != null ? built : startPiece(id, markers);
    }

    @Nullable
    private static StructureData buildWhole(String id, boolean markers) {
        if (CompositeStructures.has(id)) {
            return CompositeStructures.build(id, markers);
        }
        if (BakedStructures.has(id)) {
            return BakedStructures.build(id, markers);
        }
        if (TwilightStructures.has(id)) {
            return TwilightStructures.build(id, markers);
        }
        if (AetherStructures.has(id)) {
            return AetherStructures.build(id, markers);
        }
        if (GaiaStructures.has(id)) {
            return GaiaStructures.build(id, markers);
        }
        return JigsawAssembler.assemble(id, markers);
    }

    /**
     * Last resort: the piece the structure would have been built outward from.
     *
     * <p>A whole structure that yields nothing used to become a placeholder - the author had
     * picked "▣ Ancient city" and got an empty frame, with no way to tell a broken assembly
     * from a missing mod. Its root piece is a real room of the real structure, so showing
     * that is both honest and useful, and it costs nothing when the assembler succeeds.</p>
     */
    @Nullable
    private static StructureData startPiece(String id, boolean markers) {
        String start = JigsawAssembler.startTemplate(id);
        if (start == null) {
            return null;
        }
        StructureData piece = StructureLoader.load(start, markers);
        if (piece != null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Could not build {} whole; showing its start piece {}",
                    id, start);
        }
        return piece;
    }
}
