package net.gabriele333.gabrielequests.client.structure;

import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The catalogue of world-structure data the installed mods ship. One walk per mod file
 * collects the three kinds we need:
 *
 * <ul>
 *   <li>{@code data/<ns>/structure/**.nbt} - the templates, i.e. the single rooms/houses a
 *       structure is built from (and, for the simpler structures, the whole thing);</li>
 *   <li>{@code data/<ns>/worldgen/structure/*.json} - the structure definitions, which say
 *       whether a structure is jigsaw-assembled and from which pool it starts;</li>
 *   <li>{@code data/<ns>/worldgen/template_pool/**.json} - the pools the jigsaw solver
 *       draws pieces from.</li>
 * </ul>
 *
 * <p>Vanilla is in there too (the client jar carries {@code data/minecraft}: ~1180
 * templates, 34 structures, 186 pools), so the picker is never empty.</p>
 *
 * <p><b>Why mod files and not the data pack manager.</b> All of this is data-pack data, and
 * a client connected to a server has no access to the server's data packs - the structure
 * and template-pool registries are never synced to clients. Mod jars, on the other hand,
 * are present and identical on both sides, so scanning them gives every player the same
 * catalogue whether they are in single player or on a server, and a display structure an
 * author creates renders for everyone who has the mod. The price is that structures added
 * by a pack's own data pack (rather than by a mod) are not offered.</p>
 *
 * <p>The scan is lazy (first menu open or first render), done once per session and never
 * throws: a mod file that cannot be walked is logged and skipped.</p>
 */
public final class StructureIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");
    private static final String NBT = ".nbt";
    private static final String JSON = ".json";

    /** What kind of resource a subdirectory of {@code data/<ns>} holds. */
    private record Catalogue(Map<String, Path> templates,
                             Map<String, Path> structures,
                             Map<String, Path> pools) {
    }

    @Nullable
    private static Catalogue catalogue;

    private StructureIndex() {
    }

    /** Every template id, {@code namespace:path/without/extension}, sorted. */
    public static List<String> ids() {
        return List.copyOf(catalogue().templates().keySet());
    }

    /** The template ids of one namespace only - what a scoped picker uses. */
    public static List<String> ids(String namespace) {
        String prefix = namespace + ":";
        return catalogue().templates().keySet().stream().filter(id -> id.startsWith(prefix)).toList();
    }

    /** Every namespace that ships at least one template, sorted (ids already are). */
    public static List<String> namespaces() {
        return catalogue().templates().keySet().stream()
                .map(StructureIndex::namespaceOf).distinct().toList();
    }

    /** The template file for an id, or {@code null} when no installed mod provides it. */
    @Nullable
    public static Path path(String id) {
        return catalogue().templates().get(id);
    }

    /** The {@code worldgen/structure} definition file for an id. */
    @Nullable
    public static Path structurePath(String id) {
        return catalogue().structures().get(id);
    }

    /** The {@code worldgen/template_pool} file for an id. */
    @Nullable
    public static Path poolPath(String id) {
        return catalogue().pools().get(id);
    }

    /** Every {@code worldgen/structure} id, sorted. */
    public static List<String> structureIds() {
        return List.copyOf(catalogue().structures().keySet());
    }

    public static boolean isEmpty() {
        return catalogue().templates().isEmpty();
    }

    /**
     * The id a fresh display structure starts on: the vanilla default when present,
     * otherwise simply the first template available (so the picker always opens on
     * something that renders).
     */
    public static String defaultStructure() {
        Map<String, Path> templates = catalogue().templates();
        if (templates.containsKey(StructureSpec.DEFAULT_STRUCTURE)) {
            return StructureSpec.DEFAULT_STRUCTURE;
        }
        return templates.isEmpty() ? StructureSpec.DEFAULT_STRUCTURE : templates.keySet().iterator().next();
    }

    /** The namespace part of an id; {@code "minecraft"} when it has none. */
    public static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    /** The path part of an id, without the namespace. */
    public static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /**
     * A human-readable label: {@code "<Mod name> / <path>"}, e.g.
     * {@code "The Twilight Forest / Lich tower / Tower foyer"}. The mod name comes from the
     * loaded mod list so a pack's picker reads in the same words as its mod list; unknown
     * namespaces fall back to the raw namespace.
     */
    public static Component label(String id) {
        return Component.literal(modName(namespaceOf(id)) + " / " + prettyPath(pathOf(id)));
    }

    /** The same label without the mod prefix, for a picker already scoped to one mod. */
    public static Component shortLabel(String id) {
        return Component.literal(prettyPath(pathOf(id)));
    }

    /** The display name of the mod owning a namespace ("twilightforest" -> "The Twilight Forest"). */
    public static String modName(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    /** {@code "lich_tower/tower_foyer"} -> {@code "Lich tower / Tower foyer"}. */
    public static String prettyPath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!sb.isEmpty()) {
                sb.append(" / ");
            }
            String words = segment.replace('_', ' ');
            sb.append(words.isEmpty() ? words
                    : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1));
        }
        return sb.toString();
    }

    private static synchronized Catalogue catalogue() {
        if (catalogue == null) {
            catalogue = scan();
        }
        return catalogue;
    }

    private static Catalogue scan() {
        long started = System.currentTimeMillis();
        List<Map.Entry<String, Path>> templates = new ArrayList<>();
        List<Map.Entry<String, Path>> structures = new ArrayList<>();
        List<Map.Entry<String, Path>> pools = new ArrayList<>();
        ModList.get().forEachModFile(modFile -> {
            try {
                collect(modFile.findResource("data"), templates, structures, pools);
            } catch (Throwable t) {
                // A mod file with an exotic layout must not cost us the whole catalogue.
                LOGGER.warn("[GabrieleQuests/QuestsTools] Could not scan {} for structures",
                        modFile.getFileName(), t);
            }
        });
        Catalogue result = new Catalogue(sorted(templates), sorted(structures), sorted(pools));
        LOGGER.info("[GabrieleQuests/QuestsTools] Indexed {} structure templates, {} structure definitions "
                        + "and {} template pools in {} ms",
                result.templates().size(), result.structures().size(), result.pools().size(),
                System.currentTimeMillis() - started);
        return result;
    }

    private static Map<String, Path> sorted(List<Map.Entry<String, Path>> found) {
        found.sort(Map.Entry.comparingByKey());
        Map<String, Path> map = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : found) {
            // First mod file wins if two ship the same id, close enough to data-pack load
            // order for a picker.
            map.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return map;
    }

    /** Collects the three resource kinds under {@code <data>/<namespace>/}. */
    private static void collect(@Nullable Path dataDir,
                                List<Map.Entry<String, Path>> templates,
                                List<Map.Entry<String, Path>> structures,
                                List<Map.Entry<String, Path>> pools) throws Exception {
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            return;
        }
        try (Stream<Path> namespaces = Files.list(dataDir)) {
            for (Path namespaceDir : namespaces.toList()) {
                String namespace = fileName(namespaceDir);
                collectFrom(namespaceDir.resolve("structure"), namespace, NBT, templates);
                collectFrom(namespaceDir.resolve("worldgen").resolve("structure"), namespace, JSON, structures);
                collectFrom(namespaceDir.resolve("worldgen").resolve("template_pool"), namespace, JSON, pools);
            }
        }
    }

    private static void collectFrom(Path root, String namespace, String extension,
                                    List<Map.Entry<String, Path>> out) throws Exception {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.toList()) {
                if (!fileName(file).endsWith(extension) || !Files.isRegularFile(file)) {
                    continue;
                }
                // Jar paths use "/" already, but a mod exploded on disk (dev runtime) hands
                // us the platform separator.
                String relative = root.relativize(file).toString().replace('\\', '/');
                out.add(Map.entry(namespace + ":" + relative.substring(0, relative.length() - extension.length()),
                        file));
            }
        }
    }

    /** Last path element without a trailing separator (jar file systems like to add one). */
    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().replace("/", "").replace("\\", "");
    }
}
