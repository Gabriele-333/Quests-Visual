package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.NameMap;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockType;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockIcon;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * "Display multiblock" = a decorative {@link ChapterImage} whose icon is a
 * {@link MultiblockIcon}, mirroring {@link DisplayItemCreator} one-to-one: same creation
 * flow (build client-side, hand it to the server with {@code CreateObjectMessage}), same
 * persistence trick (the icon serialises to a short
 * string via {@code toString()} and is rebuilt by our {@code IconMixin} on
 * {@code Icon.getIcon}).
 *
 * <p>The multiblock is customised through an FTB Library config screen (type, exterior
 * size, glass walls). The int rows are range-limited and every setter re-normalises via
 * {@link MultiblockSpec#normalized()}, so the spec that reaches the icon is always valid
 * for Mekanism - validation happens on creation and on edit, never during rendering.</p>
 */
public final class MultiblockCreator {

    private static Boolean available;

    private MultiblockCreator() {
    }

    /** True if at least one supported multiblock has all its Mekanism blocks registered. */
    public static boolean isAvailable() {
        if (available == null) {
            available = !MultiblockType.availableTypes().isEmpty();
        }
        return available;
    }

    /** Representative item per mod group, used as the icon in the mod-selection menu. */
    private static final Map<String, String> GROUP_ICONS = Map.of(
            "mekanism", "mekanism:steel_casing",
            "ae2", "ae2:controller",
            "enderio", "enderio:basic_capacitor_bank",
            "draconicevolution", "draconicevolution:reactor_core",
            "ars_nouveau", "ars_nouveau:enchanting_apparatus",
            "pneumaticcraft", "pneumaticcraft:pressure_chamber_valve",
            "mysticalagriculture", "mysticalagriculture:infusion_altar");

    /**
     * Entry point from the map's right-click menu: first a context menu to pick the
     * <b>mod</b> (same fresh-menu-at-mouse flow as ShapeMenu - never
     * {@code ContextMenuItem.subMenu}, see the spec), then the config screen scoped to
     * that mod's multiblocks. With a single available mod the menu is skipped.
     */
    public static void open(QuestScreen screen, double x, double y) {
        Chapter chapter = screen.getSelectedChapter().orElse(null);
        List<String> groups = MultiblockType.availableModGroups();
        if (chapter == null || groups.isEmpty()) {
            return;
        }
        if (groups.size() == 1) {
            openConfig(screen, chapter, x, y, groups.get(0));
            return;
        }
        List<ContextMenuItem> menu = new ArrayList<>();
        menu.add(ContextMenuItem.title(Component.translatable("gabrielequests.multiblock.choose_mod")));
        menu.add(ContextMenuItem.SEPARATOR);
        for (String group : groups) {
            menu.add(new ContextMenuItem(
                    Component.translatable("gabrielequests.multiblock.mod." + group),
                    groupIcon(group),
                    button -> openConfig(screen, chapter, x, y, group)));
        }
        screen.openContextMenu(menu);
    }

    /**
     * Opens the multiblock config screen scoped to one mod group and, on accept, creates
     * the display multiblock at the given quest-space coordinates.
     */
    private static void openConfig(QuestScreen screen, Chapter chapter, double x, double y,
                                   String modGroup) {
        List<MultiblockType> types = MultiblockType.availableTypes(modGroup);
        if (types.isEmpty()) {
            return;
        }
        MultiblockSpec[] value = {new MultiblockSpec(types.get(0), 5, 5, 5, true, Map.of()).normalized()};
        ConfigGroup group = new ConfigGroup("gabrielequests_multiblock", accepted -> {
            if (accepted) {
                create(chapter, value[0], x, y);
            }
            // The config screen is a full screen; return to the quest editor on close.
            screen.openGui();
        });
        addSpecConfigs(group, "type", value[0], spec -> value[0] = spec, types);
        new EditConfigScreen(group).openGui();
    }

    private static Icon groupIcon(String group) {
        String itemId = GROUP_ICONS.get(group);
        if (itemId != null) {
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                return ItemIcon.getItemIcon(new ItemStack(BuiltInRegistries.ITEM.get(rl)));
            }
        }
        return Icons.ADD;
    }

    private static void create(Chapter chapter, MultiblockSpec spec, double x, double y) {
        ChapterImage image = new ChapterImage(0L, chapter);
        image.setImage(new MultiblockIcon(spec));
        image.setPosition(x, y);
        image.fixupAspectRatio(false);
        NetworkManager.sendToServer(CreateObjectMessage.requestCreation(image));
    }

    /**
     * Adds the spec rows (type, width, height, depth, glass, view angles) to a config group. Shared
     * between the creation screen and the {@code ChapterImageMixin} properties-screen fix,
     * where {@code typeConfigId} is {@code "image"} so the row <em>replaces</em> the
     * destructive {@code ImageResourceConfig} entry (same LinkedHashMap trick as the item
     * icon fix).
     *
     * <p>{@code ConfigGroup#save} applies every setter in row order on accept; each setter
     * folds its field into a running spec and hands the (re-normalised) result to
     * {@code apply}, so after the last setter the applied spec carries all edits.</p>
     */
    public static void addSpecConfigs(ConfigGroup group, String typeConfigId,
                                      MultiblockSpec initial, Consumer<MultiblockSpec> apply) {
        addSpecConfigs(group, typeConfigId, initial, apply, null);
    }

    /** As above; {@code typeChoices} restricts the type selector (null = all available). */
    public static void addSpecConfigs(ConfigGroup group, String typeConfigId,
                                      MultiblockSpec initial, Consumer<MultiblockSpec> apply,
                                      @Nullable List<MultiblockType> typeChoices) {
        MultiblockSpec[] current = {initial.normalized()};
        Consumer<MultiblockSpec> update = spec -> {
            current[0] = spec;
            apply.accept(spec);
        };
        List<MultiblockType> types = new ArrayList<>(
                typeChoices != null ? typeChoices : MultiblockType.availableTypes());
        if (!types.contains(current[0].type())) {
            types.add(0, current[0].type()); // keep an unavailable type selectable as-is
        }
        NameMap<MultiblockType> nameMap = NameMap.of(current[0].type(), types)
                .id(MultiblockType::id)
                .nameKey(MultiblockType::translationKey)
                .create();
        group.addEnum(typeConfigId, current[0].type(),
                        t -> update.accept(current[0].withType(t)), nameMap)
                .setNameKey("gabrielequests.multiblock.type");
        // "mb_"-prefixed ids: ChapterImage's own config rows already use "width"/"height"
        // (the on-map image size), and adding a row under an existing id REPLACES it -
        // colliding here would silently remove the ability to resize the image.
        group.addInt("mb_width", current[0].width(),
                        i -> update.accept(current[0].withWidth(i)), current[0].width(), 1, 18)
                .setNameKey("gabrielequests.multiblock.width");
        group.addInt("mb_height", current[0].height(),
                        i -> update.accept(current[0].withHeight(i)), current[0].height(), 1, 18)
                .setNameKey("gabrielequests.multiblock.height");
        group.addInt("mb_depth", current[0].depth(),
                        i -> update.accept(current[0].withDepth(i)), current[0].depth(), 1, 18)
                .setNameKey("gabrielequests.multiblock.depth");
        group.addBool("mb_glass", current[0].glass(),
                        b -> update.accept(current[0].withGlass(b)), true)
                .setNameKey("gabrielequests.multiblock.glass");
        group.addBool("mb_view_rotate", current[0].viewRotate(),
                        b -> update.accept(current[0].withViewRotate(b)), false)
                .setNameKey("gabrielequests.multiblock.view_rotate");
        // The orbit angles, typeable instead of only draggable. Each row folds into the
        // running spec (yaw first, then pitch), so setting both in one screen works.
        group.addInt("mb_yaw", current[0].yaw(),
                        v -> update.accept(current[0].withRotation(v, current[0].pitch())),
                        MultiblockSpec.DEFAULT_YAW, 0, 359)
                .setNameKey("gabrielequests.display.yaw");
        group.addInt("mb_pitch", current[0].pitch(),
                        v -> update.accept(current[0].withRotation(current[0].yaw(), v)),
                        MultiblockSpec.DEFAULT_PITCH, -89, 89)
                .setNameKey("gabrielequests.display.pitch");
        // Per-type block options (tiers, layouts, ...): the union across the offered
        // types is shown; a row only takes effect when its type is selected, because the
        // type row is applied first (insertion order) and normalisation drops options the
        // final type does not declare.
        Set<String> seen = new HashSet<>();
        for (MultiblockType type : types) {
            for (MultiblockType.Option option : type.options()) {
                if (!seen.add(option.id())) {
                    continue;
                }
                NameMap<String> choices = NameMap.of(option.defaultChoice(), option.choices())
                        .id(c -> c)
                        .nameKey(c -> "gabrielequests.multiblock." + option.id() + "." + c)
                        .create();
                String value = current[0].option(option.id());
                group.addEnum(option.id(),
                                option.choices().contains(value) ? value : option.defaultChoice(),
                                v -> update.accept(current[0].withOption(option.id(), v)), choices)
                        .setNameKey("gabrielequests.multiblock." + option.id());
            }
        }
    }
}
