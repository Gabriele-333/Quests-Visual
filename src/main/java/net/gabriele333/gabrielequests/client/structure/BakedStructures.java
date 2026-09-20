package net.gabriele333.gabrielequests.client.structure;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Structures that were generated once, by their own mod's generator, and shipped with this mod
 * as ordinary {@code .nbt} templates - see {@code dev/StructureBaker} for how they are made and
 * why they have to be made in advance rather than on a player's client.
 *
 * <p>There is no registry to maintain: a baked structure is recognised <b>by where it lives</b>.
 * {@code twilightforest:dark_tower} is baked if this mod ships a template called
 * {@code gabrielequests:twilightforest/dark_tower}, so adding a structure is dropping a file in
 * {@code data/gabrielequests/structure/<namespace>/<path>.nbt} and nothing else. Because
 * {@link StructureIndex} scans mod files for exactly that layout, the discovery is free.</p>
 *
 * <p>The template is only offered when the structure it stands for is <em>also</em> registered
 * in the current mod set. A pack without Twilight Forest would otherwise be shown a dark tower
 * it does not have, made of blocks it cannot resolve.</p>
 */
public final class BakedStructures {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /**
     * The data namespace baked templates live under. This is {@code gabrielequests}, the one
     * this mod uses for its assets and lang keys - <b>not</b> the mod id, which is
     * {@code questsvisual} after the rebrand.
     */
    public static final String NAMESPACE = "gabrielequests";

    @Nullable
    private static List<String> ids;

    private BakedStructures() {
    }

    /** The real structure ids this mod ships a baked instance of, sorted. */
    public static synchronized List<String> ids() {
        if (ids == null) {
            List<String> found = new ArrayList<>();
            List<String> structures = StructureIndex.structureIds();
            for (String template : StructureIndex.ids(NAMESPACE)) {
                // "gabrielequests:twilightforest/dark_tower" -> "twilightforest:dark_tower"
                String path = StructureIndex.pathOf(template);
                int slash = path.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                String id = path.substring(0, slash) + ":" + path.substring(slash + 1);
                if (structures.contains(id)) {
                    found.add(id);
                }
            }
            found.sort(null);
            ids = List.copyOf(found);
            if (!found.isEmpty()) {
                LOGGER.info("[GabrieleQuests/QuestsTools] {} structures available as baked instances",
                        found.size());
            }
        }
        return ids;
    }

    public static boolean has(String id) {
        return ids().contains(id);
    }

    /** The template that holds the baked instance of a structure. */
    public static String templateId(String id) {
        return NAMESPACE + ":" + StructureIndex.namespaceOf(id) + "/" + StructureIndex.pathOf(id);
    }

    /**
     * The baked blocks, or {@code null} when the template is missing or unreadable. Markers
     * are honoured like any other template, though a baked structure carries none: it is what
     * the generator actually placed in a world, not a world-generation blueprint.
     */
    @Nullable
    public static StructureData build(String id, boolean markers) {
        return StructureLoader.load(templateId(id), markers);
    }
}
