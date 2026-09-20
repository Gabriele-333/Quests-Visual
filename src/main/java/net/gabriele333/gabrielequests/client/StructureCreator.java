package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.NameMap;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.structure.BakedStructures;
import net.gabriele333.gabrielequests.client.structure.StructureIcon;
import net.gabriele333.gabrielequests.client.structure.StructureIndex;
import net.gabriele333.gabrielequests.client.structure.StructureSpec;
import net.gabriele333.gabrielequests.client.structure.WholeStructures;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Display structure" = a decorative {@link ChapterImage} whose icon is a
 * {@link StructureIcon}, mirroring {@link MultiblockCreator} one-to-one: same creation flow
 * (build client-side, hand it to the server with {@code CreateObjectMessage}), same
 * persistence trick (the icon serialises to a short string via {@code toString()} and is
 * rebuilt by our {@code IconMixin} on {@code Icon.getIcon}).
 *
 * <p>Where the display multiblock offers a fixed catalogue of hand-written layouts, this
 * one offers <em>every</em> structure template installed in the pack - vanilla's village
 * houses and ancient city rooms, Twilight Forest's dungeon pieces, whatever any other mod
 * ships (see {@link StructureIndex}). Because that catalogue runs into the thousands, the
 * mod is picked first in a context menu (same fresh-menu-at-mouse flow as
 * {@link ShapeMenu} - never {@code ContextMenuItem.subMenu}, see the spec) and the config
 * screen's picker is scoped to that mod.</p>
 */
public final class StructureCreator {

    private StructureCreator() {
    }

    /** True when any installed mod ships structure templates (vanilla always does). */
    public static boolean isAvailable() {
        return !StructureIndex.isEmpty();
    }

    /** Entry point from the map's right-click menu: pick the mod, then configure. */
    public static void open(QuestScreen screen, double x, double y) {
        Chapter chapter = screen.getSelectedChapter().orElse(null);
        List<String> namespaces = namespaces();
        if (chapter == null || namespaces.isEmpty()) {
            return;
        }
        if (namespaces.size() == 1) {
            openConfig(screen, chapter, x, y, namespaces.get(0));
            return;
        }
        List<ContextMenuItem> menu = new ArrayList<>();
        menu.add(ContextMenuItem.title(Component.translatable("gabrielequests.structure.choose_mod")));
        menu.add(ContextMenuItem.SEPARATOR);
        for (String namespace : namespaces) {
            menu.add(new ContextMenuItem(
                    Component.literal(StructureIndex.modName(namespace)),
                    Icons.ADD,
                    button -> openConfig(screen, chapter, x, y, namespace)));
        }
        screen.openContextMenu(menu);
    }

    /**
     * Opens the structure config screen scoped to one mod and, on accept, creates the
     * display structure at the given quest-space coordinates.
     */
    private static void openConfig(QuestScreen screen, Chapter chapter, double x, double y,
                                   String namespace) {
        List<String> choices = choices(namespace);
        if (choices.isEmpty()) {
            return;
        }
        String first = choices.get(0);
        StructureSpec[] value = {StructureSpec.defaultSpec()
                .withStructure(isWhole(first), idOf(first))};
        ConfigGroup group = new ConfigGroup("gabrielequests_structure", accepted -> {
            if (accepted) {
                create(chapter, value[0], x, y);
            }
            // The config screen is a full screen; return to the quest editor on close.
            screen.openGui();
        });
        addSpecConfigs(group, "structure", value[0], spec -> value[0] = spec);
        new EditConfigScreen(group).openGui();
    }

    private static void create(Chapter chapter, StructureSpec spec, double x, double y) {
        ChapterImage image = new ChapterImage(0L, chapter);
        image.setImage(new StructureIcon(spec));
        image.setPosition(x, y);
        image.fixupAspectRatio(false);
        NetworkManager.sendToServer(CreateObjectMessage.requestCreation(image));
    }

    /**
     * Adds the spec rows (structure, mod, view-rotate, markers, view angles) to a config group. Shared
     * between the creation screen and the {@code ChapterImageMixin} properties-screen fix,
     * where {@code structureConfigId} is {@code "image"} so the row <em>replaces</em> the
     * destructive {@code ImageResourceConfig} entry (same LinkedHashMap trick as the item,
     * multiblock, mannequin and entity icons).
     *
     * <p>{@code ConfigGroup#save} applies every setter in row order on accept; each folds
     * its field into a running spec and hands the result to {@code apply}, so after the last
     * row the applied spec carries all edits. The <b>mod row comes after the structure
     * row</b> and that ordering is load-bearing: the structure picker can only list one
     * mod's structures, so switching mods means the structure row still holds an id from the
     * old one. Applying the mod last lets it override that with the new mod's first
     * structure, and reopening the screen then lists the new mod's catalogue. Leaving the
     * mod row alone makes it a no-op, so editing just the structure works as expected.</p>
     */
    public static void addSpecConfigs(ConfigGroup group, String structureConfigId,
                                      StructureSpec initial, Consumer<StructureSpec> apply) {
        StructureSpec[] current = {initial.normalized()};
        Consumer<StructureSpec> update = spec -> {
            current[0] = spec;
            apply.accept(spec);
        };
        String namespace = StructureIndex.namespaceOf(current[0].structureId());

        // The chosen mod's structures: complete ones first, then the individual pieces they
        // are built from. Both live in one picker (values carry their kind as a prefix), so
        // switching between "whole village" and "one village house" is a single choice
        // instead of a second row that would need the list to refresh.
        List<String> choices = new ArrayList<>(choices(namespace));
        String selected = value(current[0].whole(), current[0].structureId());
        // Keep the current one selectable even if its mod is gone, so an existing display
        // structure round-trips.
        if (!choices.contains(selected)) {
            choices.add(0, selected);
        }
        NameMap<String> structures = NameMap.of(selected, choices)
                .id(s -> s)
                .name(StructureCreator::choiceLabel)
                .create();
        group.addEnum(structureConfigId, selected,
                        choice -> update.accept(current[0].withStructure(isWhole(choice), idOf(choice))),
                        structures)
                .setNameKey("gabrielequests.structure.structure");

        List<String> namespaces = new ArrayList<>(namespaces());
        if (!namespaces.contains(namespace)) {
            namespaces.add(0, namespace);
        }
        NameMap<String> mods = NameMap.of(namespace, namespaces)
                .id(s -> s)
                .name(ns -> Component.literal(StructureIndex.modName(ns)))
                .create();
        group.addEnum("st_mod", namespace, chosen -> {
                    if (!chosen.equals(namespace)) {
                        List<String> chosenChoices = choices(chosen);
                        if (!chosenChoices.isEmpty()) {
                            String first = chosenChoices.get(0);
                            update.accept(current[0].withStructure(isWhole(first), idOf(first)));
                        }
                    }
                }, mods)
                .setNameKey("gabrielequests.structure.mod");

        group.addBool("st_view_rotate", current[0].viewRotate(),
                        b -> update.accept(current[0].withViewRotate(b)), false)
                .setNameKey("gabrielequests.structure.view_rotate");
        group.addBool("st_markers", current[0].markers(),
                        b -> update.accept(current[0].withMarkers(b)), false)
                .setNameKey("gabrielequests.structure.markers");
        // The orbit angles, typeable instead of only draggable. Each row folds into the
        // running spec (yaw first, then pitch), so setting both in one screen works.
        group.addInt("st_yaw", current[0].yaw(),
                        v -> update.accept(current[0].withRotation(v, current[0].pitch())),
                        StructureSpec.DEFAULT_YAW, 0, 359)
                .setNameKey("gabrielequests.display.yaw");
        group.addInt("st_pitch", current[0].pitch(),
                        v -> update.accept(current[0].withRotation(current[0].yaw(), v)),
                        StructureSpec.DEFAULT_PITCH, -89, 89)
                .setNameKey("gabrielequests.display.pitch");
    }

    // ---- picker values -------------------------------------------------------------
    // One list has to hold two kinds of thing whose ids could in principle collide (a
    // structure "foo:village" and a template "foo:village" are different files), so each
    // value carries its kind. "|" is safe: it appears in neither namespace nor path.

    private static final String WHOLE_PREFIX = "w|";
    private static final String PIECE_PREFIX = "p|";

    private static String value(boolean whole, String id) {
        return (whole ? WHOLE_PREFIX : PIECE_PREFIX) + id;
    }

    private static boolean isWhole(String value) {
        return value.startsWith(WHOLE_PREFIX);
    }

    private static String idOf(String value) {
        return value.startsWith(WHOLE_PREFIX) || value.startsWith(PIECE_PREFIX)
                ? value.substring(2) : value;
    }

    /** Complete structures first, then the individual templates, for one mod. */
    private static List<String> choices(String namespace) {
        List<String> choices = new ArrayList<>();
        for (String id : WholeStructures.ids(namespace)) {
            choices.add(WHOLE_PREFIX + id);
        }
        if (!isOurs(namespace)) {
            for (String id : StructureIndex.ids(namespace)) {
                choices.add(PIECE_PREFIX + id);
            }
        }
        return choices;
    }

    /** Mods offering either kind. */
    private static List<String> namespaces() {
        List<String> namespaces = new ArrayList<>();
        for (String namespace : StructureIndex.namespaces()) {
            if (!isOurs(namespace)) {
                namespaces.add(namespace);
            }
        }
        for (String namespace : WholeStructures.namespaces()) {
            if (!namespaces.contains(namespace)) {
                namespaces.add(namespace);
            }
        }
        namespaces.sort(null);
        return namespaces;
    }

    /**
     * Our own templates are the {@linkplain net.gabriele333.gabrielequests.client.structure.BakedStructures
     * baked instances}, and they are offered under the mod they belong to, as whole
     * structures. Listing them again here would put a phantom "Quests Visual" mod in the
     * picker holding second copies of other mods' dungeons.
     */
    private static boolean isOurs(String namespace) {
        return namespace.equals(BakedStructures.NAMESPACE);
    }

    /** Whole structures are marked so the two kinds are told apart at a glance. */
    private static Component choiceLabel(String value) {
        String id = idOf(value);
        return isWhole(value)
                ? Component.translatable("gabrielequests.structure.whole",
                        StructureIndex.prettyPath(StructureIndex.pathOf(id)))
                : StructureIndex.shortLabel(id);
    }
}
