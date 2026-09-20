package net.gabriele333.gabrielequests.client.portal;

import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The portals a display portal can show, and the default structure of each.
 *
 * <p>Two sources, in this order:</p>
 * <ol>
 *   <li><b>Built-in defaults</b> ({@link #BUILT_IN}): portals whose real structure is known,
 *       so a fresh display looks like the thing it depicts without the author configuring
 *       anything - the vanilla three (nether portal in its obsidian frame, end portal ring
 *       with eyed frames, end gateway cage), plus the Aether's glowstone frame, the Gaia
 *       Dimension's keystone one, and the Twilight Forest's pool in a flowered ring of grass
 *       (its portal tags say exactly that: {@code portal/fluid} = water,
 *       {@code portal/edge} = dirt-ish, {@code portal/generated_decoration} = flowers and
 *       mushrooms).</li>
 *   <li><b>Discovery</b>: every other block registered under an id that ends in
 *       {@code _portal} (or is exactly {@code portal}), whatever mod added it. Those get an
 *       upright frame of the best frame block that can be found in the same mod, or obsidian.
 *       A guessed default is not always right, which is why the frame, shape and size are all
 *       editable - the guess only decides where the author starts.</li>
 * </ol>
 *
 * <p>Discovery works off {@link BuiltInRegistries#BLOCK}, which is identical on a client and
 * the server it is connected to, so every player sees the same catalogue and a display an
 * author creates renders for everyone with the same mods (same reasoning as
 * {@code StructureIndex}, without the data-pack caveat). The scan is one pass over the block
 * registry, done lazily and once per session.</p>
 *
 * <p>Blocks a mod does not provide are dropped from the list, so the picker never offers a
 * portal that cannot render. An existing display whose mod is gone keeps its string and draws
 * a placeholder (see {@link PortalIcon}).</p>
 */
public final class PortalCatalog {

    private static final String OBSIDIAN = "minecraft:obsidian";

    /**
     * Known portals with their real structure. Entries whose portal block is not registered
     * are skipped, so listing a mod here costs nothing when the mod is absent - and an entry
     * whose frame is that same mod's block goes with it, since the two stand or fall together.
     */
    private static final List<PortalSpec> BUILT_IN = List.of(
            // Obsidian frame, 2x3 interior minimum, corners included (the usual player build).
            arch("minecraft:nether_portal", OBSIDIAN),
            // 12 eyed frames around a 3x3 surface, no corners - the stronghold's ring.
            flat("minecraft:end_portal", "minecraft:end_portal_frame", 3, 3, false, false, false, true),
            gateway("minecraft:end_gateway", "minecraft:bedrock"),
            // The Aether: nether-portal geometry, glowstone frame, filled with water.
            arch("aether:aether_portal", "minecraft:glowstone"),
            // The Gaia Dimension: vanilla's portal geometry down to the 2x3..21x21 interior
            // (GaiaPortalBlock.Size is PortalShape with one block swapped), in keystone. Worth
            // an entry even though discovery would find the portal block anyway: the mod has no
            // "*_frame" block for the frame guess to land on, so it would default to obsidian.
            arch("gaiadimension:gaia_portal", "gaiadimension:keystone_block"),
            // The Twilight Forest: a 2x2 pool sunk in flowered ground, no frame block as such.
            flat("twilightforest:twilight_portal", "minecraft:grass_block", 2, 2, true, true, true, false));

    /** Lazily built list of portal block ids: built-ins first, then whatever was discovered. */
    @Nullable
    private static List<String> ids;

    private PortalCatalog() {
    }

    /** Every portal block id this install can show, built-ins (vanilla first) then the rest. */
    public static synchronized List<String> ids() {
        if (ids == null) {
            Set<String> found = new LinkedHashSet<>();
            for (PortalSpec spec : BUILT_IN) {
                if (BlockStates.exists(spec.portalId())) {
                    found.add(spec.portalId());
                }
            }
            List<String> discovered = new ArrayList<>();
            for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
                if (looksLikePortal(id)) {
                    discovered.add(id.toString());
                }
            }
            discovered.sort(null);
            found.addAll(discovered);
            ids = List.copyOf(found);
        }
        return ids;
    }

    /** True when there is anything to show (vanilla alone guarantees it). */
    public static boolean isAvailable() {
        return !ids().isEmpty();
    }

    /**
     * The starting spec for a portal block: its built-in structure when we know one, otherwise
     * an upright frame with the best frame block guessable from the mod's own blocks. An id
     * nothing provides keeps its id (and renders as a placeholder) rather than being silently
     * swapped for another portal.
     */
    public static PortalSpec defaultsFor(String portalId) {
        for (PortalSpec spec : BUILT_IN) {
            if (spec.portalId().equals(portalId)) {
                return spec;
            }
        }
        return arch(portalId, guessFrame(portalId));
    }

    /** The portal a fresh display starts on: the nether portal, or whatever else exists. */
    public static String defaultPortal() {
        List<String> available = ids();
        if (available.contains(PortalSpec.DEFAULT_PORTAL) || available.isEmpty()) {
            return PortalSpec.DEFAULT_PORTAL;
        }
        return available.get(0);
    }

    /**
     * A human-readable label for a portal block: its own name, prefixed with the mod's display
     * name for anything but vanilla ("The Twilight Forest / Twilight Portal"), so one flat
     * picker stays readable.
     */
    public static Component label(String portalId) {
        ResourceLocation rl = ResourceLocation.tryParse(portalId);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return Component.literal(portalId);
        }
        Component name = BuiltInRegistries.BLOCK.get(rl).getName();
        if (rl.getNamespace().equals("minecraft")) {
            return name;
        }
        return Component.literal(modName(rl.getNamespace()) + " / ").append(name);
    }

    /** The display name of the mod owning a namespace, falling back to the namespace. */
    private static String modName(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    /**
     * Portal blocks are recognised by name. Deliberately narrow: {@code *_portal} and
     * {@code portal} only, never a mere "contains", which would drag in things like the
     * Twilight Forest's {@code twilight_portal_miniature_structure} (a decorative model, not a
     * portal). Frame blocks are excluded - they are the other half of a display, not a subject
     * of one.
     */
    private static boolean looksLikePortal(ResourceLocation id) {
        String path = id.getPath();
        if (path.contains("frame")) {
            return false;
        }
        return path.equals("portal") || path.endsWith("_portal");
    }

    /**
     * The most plausible frame for a portal we have no recipe for: a same-mod block whose name
     * pairs the portal's with "frame", else any block of that mod whose name mentions both a
     * portal and a frame, else obsidian. Only a starting point - the author picks the frame in
     * the properties screen.
     */
    private static String guessFrame(String portalId) {
        ResourceLocation rl = ResourceLocation.tryParse(portalId);
        if (rl == null) {
            return OBSIDIAN;
        }
        String namespace = rl.getNamespace();
        String path = rl.getPath();
        String stem = path.endsWith("_portal") ? path.substring(0, path.length() - "_portal".length()) : path;
        for (String candidate : List.of(path + "_frame", stem + "_frame", stem + "_portal_frame")) {
            String id = namespace + ":" + candidate;
            if (BlockStates.exists(id)) {
                return id;
            }
        }
        for (ResourceLocation other : BuiltInRegistries.BLOCK.keySet()) {
            if (other.getNamespace().equals(namespace)
                    && other.getPath().contains("portal") && other.getPath().contains("frame")) {
                return other.toString();
            }
        }
        return OBSIDIAN;
    }

    // ---- built-in entry shorthands -------------------------------------------------------

    /** Upright frame, vanilla's minimum interior (2 wide, 3 tall), corners included. */
    private static PortalSpec arch(String portalId, String frameId) {
        return spec(portalId, frameId, PortalShape.ARCH, 2, 3, 1, true, false, false, true);
    }

    private static PortalSpec flat(String portalId, String frameId, int width, int depth,
                                   boolean corners, boolean base, boolean decor, boolean eyes) {
        return spec(portalId, frameId, PortalShape.FLAT, width, 1, depth, corners, base, decor, eyes);
    }

    private static PortalSpec gateway(String portalId, String frameId) {
        return spec(portalId, frameId, PortalShape.GATEWAY, 1, 1, 1, false, false, false, false);
    }

    /** Lit, no view-rotate, opened on the view its shape looks best from. */
    private static PortalSpec spec(String portalId, String frameId, PortalShape shape,
                                   int width, int height, int depth,
                                   boolean corners, boolean base, boolean decor, boolean eyes) {
        return new PortalSpec(portalId, frameId, shape, width, height, depth, "x",
                corners, true, base, decor, eyes,
                false, shape.defaultYaw(), shape.defaultPitch()).normalized();
    }
}
