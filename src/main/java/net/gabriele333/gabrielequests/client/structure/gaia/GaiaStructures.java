package net.gabriele333.gabrielequests.client.structure.gaia;

import net.gabriele333.gabrielequests.client.structure.StructureData;
import net.gabriele333.gabrielequests.client.structure.StructureDefinition;
import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import net.gabriele333.gabrielequests.client.structure.TemplateAssembly;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Gaia Dimension integration: its two structures, assembled here from its own generators.
 *
 * <p><b>Why it needs one.</b> Same shape of problem as the Aether. The Gaia Dimension ships 34
 * {@code .nbt} templates and not a single {@code template_pool}, and its templates carry no
 * jigsaw blocks either - the towers are stacked by Java at generation time. So both generic
 * paths come up empty: {@code JigsawDefinitions#isAssemblable} says no (no start pool, and the
 * templates are filed under {@code watchtower/} and {@code minitower/}, not under the
 * structures' own paths, so its template fallback finds nothing to index), and the two
 * structures were simply missing from the "whole" half of the picker. The individual floors
 * have always worked as single pieces, and still do; this is about the whole tower.</p>
 *
 * <p><b>How faithful.</b> Both builders are ports of the mod's own stacking maths - the same
 * accumulated heights, the same per-part offsets, the same random draws over the same template
 * sets. What they leave out is what needs a world: the terrain each piece would settle onto,
 * the degrade processors that weather the brickwork block by block, and the loot and spawners
 * the data markers become. Each builder's class note says where it simplifies.</p>
 *
 * <p><b>Dispatch is by structure type</b>, like the Twilight Forest and Aether integrations, so
 * a pack that registers a second tower of an existing type gets a display without anything here
 * changing. The template ids, though, are the mod's own hard-coded {@code gaiadimension:}
 * ones - that is what {@code MalachiteWatchtowerPieces#makePiece} builds whichever structure is
 * being generated, so it is what a faithful port has to ask for. Nothing in this package is a
 * compile-time or hard runtime dependency: templates are looked up by id, so with the mod
 * absent both builders produce nothing, the entries disappear from the picker, and an existing
 * display falls back to a placeholder.</p>
 */
public final class GaiaStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** The namespace the mod files its own templates under; see the class note. */
    static final String NAMESPACE = "gaiadimension";

    /** Structure type -> builder. See the class note on why this is keyed by type. */
    private static final Map<String, Consumer<TemplateAssembly>> BUILDERS = Map.of(
            "gaiadimension:malachite_watchtower", MalachiteWatchtowerBuilder::build,
            "gaiadimension:mini_tower", MiniTowerBuilder::build);

    @Nullable
    private static List<String> ids;
    /** Built results keyed by (id, markers); a {@code null} value memoises a failure. */
    private static final Map<String, StructureData> CACHE = new HashMap<>();

    private GaiaStructures() {
    }

    /** Every structure this integration can build, sorted. */
    public static synchronized List<String> ids() {
        if (ids == null) {
            List<String> found = new ArrayList<>();
            for (String id : StructureIndex.structureIds()) {
                if (BUILDERS.containsKey(StructureDefinition.typeOf(id))) {
                    found.add(id);
                }
            }
            ids = List.copyOf(found);
            if (!found.isEmpty()) {
                LOGGER.info("[GabrieleQuests/QuestsTools] {} Gaia Dimension structures can be built "
                        + "from their generators", found.size());
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
            Consumer<TemplateAssembly> builder = BUILDERS.get(StructureDefinition.typeOf(id));
            if (builder != null) {
                TemplateAssembly assembly = new TemplateAssembly(id, "Gaia Dimension", markers);
                builder.accept(assembly);
                built = assembly.finish();
            }
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] Failed to build Gaia Dimension structure {}", id, t);
        }
        if (built == null) {
            LOGGER.warn("[GabrieleQuests/QuestsTools] Gaia Dimension structure {} produced nothing "
                    + "(its templates are probably not installed)", id);
        }
        CACHE.put(key, built);
        return built;
    }
}
