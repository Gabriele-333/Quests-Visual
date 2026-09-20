package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.mannequin.MannequinIcon;
import net.gabriele333.gabrielequests.client.mannequin.MannequinSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * "Display mannequin" = a decorative {@link ChapterImage} whose icon is a
 * {@link MannequinIcon}, mirroring {@link MultiblockCreator}/{@link DisplayItemCreator}:
 * build client-side, hand it to the server with {@code CreateObjectMessage}; the icon
 * serialises to a short string and is rebuilt by the {@code IconMixin} on
 * {@code Icon.getIcon}.
 *
 * <p>The mannequin is customised through an FTB Library config screen with one item picker
 * per equipment slot (helmet, chestplate, leggings, boots, main hand, off hand). Each
 * setter folds its slot into a running spec and rebuilds the icon; every spec is normalised,
 * so what reaches the icon is always well-formed.</p>
 */
public final class MannequinCreator {

    private MannequinCreator() {
    }

    /** Opens the mannequin config screen and, on accept, creates one at the given coords. */
    public static void open(QuestScreen screen, double x, double y) {
        Chapter chapter = screen.getSelectedChapter().orElse(null);
        if (chapter == null) {
            return;
        }
        MannequinSpec[] value = {MannequinSpec.empty()};
        ConfigGroup group = new ConfigGroup("gabrielequests_mannequin", accepted -> {
            if (accepted) {
                create(chapter, value[0], x, y);
            }
            // The config screen is a full screen; return to the quest editor on close.
            screen.openGui();
        });
        addSpecConfigs(group, "head", value[0], spec -> value[0] = spec);
        new EditConfigScreen(group).openGui();
    }

    private static void create(Chapter chapter, MannequinSpec spec, double x, double y) {
        ChapterImage image = new ChapterImage(0L, chapter);
        image.setImage(new MannequinIcon(spec));
        image.setPosition(x, y);
        makePortrait(image);
        NetworkManager.sendToServer(CreateObjectMessage.requestCreation(image));
    }

    /**
     * Adds the six equipment picker rows (plus the view angles) to a config group. Shared between the creation
     * screen and the {@code ChapterImageMixin} properties-screen fix, where
     * {@code headConfigId} is {@code "image"} so the helmet row <em>replaces</em> the
     * destructive {@code ImageResourceConfig} entry (same LinkedHashMap trick as the item
     * and multiblock icons); the remaining rows use fresh {@code mann_*} ids.
     *
     * <p>{@code ConfigGroup#save} applies every setter in row order on accept; each setter
     * folds its slot into a running spec and hands the result to {@code apply}, so after the
     * last row the applied spec carries all edits.</p>
     */
    public static void addSpecConfigs(ConfigGroup group, String headConfigId,
                                      MannequinSpec initial, Consumer<MannequinSpec> apply) {
        MannequinSpec[] current = {initial.normalized()};
        Consumer<MannequinSpec> update = spec -> {
            current[0] = spec;
            apply.accept(spec);
        };
        addSlot(group, headConfigId, "gabrielequests.mannequin.head", current[0].head(),
                id -> update.accept(current[0].withHead(id)));
        addSlot(group, "mann_chest", "gabrielequests.mannequin.chest", current[0].chest(),
                id -> update.accept(current[0].withChest(id)));
        addSlot(group, "mann_legs", "gabrielequests.mannequin.legs", current[0].legs(),
                id -> update.accept(current[0].withLegs(id)));
        addSlot(group, "mann_feet", "gabrielequests.mannequin.feet", current[0].feet(),
                id -> update.accept(current[0].withFeet(id)));
        addSlot(group, "mann_main", "gabrielequests.mannequin.main_hand", current[0].mainHand(),
                id -> update.accept(current[0].withMainHand(id)));
        addSlot(group, "mann_off", "gabrielequests.mannequin.off_hand", current[0].offHand(),
                id -> update.accept(current[0].withOffHand(id)));
        // The orbit angles, typeable instead of only draggable. Each row folds into the
        // running spec (yaw first, then pitch), so setting both in one screen works.
        group.addInt("mann_yaw", current[0].yaw(),
                        v -> update.accept(current[0].withRotation(v, current[0].pitch())),
                        MannequinSpec.DEFAULT_YAW, 0, 359)
                .setNameKey("gabrielequests.display.yaw");
        group.addInt("mann_pitch", current[0].pitch(),
                        v -> update.accept(current[0].withRotation(current[0].yaw(), v)),
                        MannequinSpec.DEFAULT_PITCH, -89, 89)
                .setNameKey("gabrielequests.display.pitch");
    }

    private static void addSlot(ConfigGroup group, String id, String nameKey, String itemId,
                                Consumer<String> setter) {
        group.addItemStack(id, stackOf(itemId), stack -> setter.accept(idOf(stack)),
                        ItemStack.EMPTY, false, false)
                .setNameKey(nameKey);
    }

    private static ItemStack stackOf(String id) {
        if (id == null || id.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(rl));
    }

    private static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Gives a fresh mannequin image a portrait (1x2) footprint; harmless if it fails. */
    private static void makePortrait(ChapterImage image) {
        try {
            setDouble(image, "width", 1.0D);
            setDouble(image, "height", 2.0D);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Non-fatal: keep the default square, the user can resize in the properties screen.
        }
    }

    private static void setDouble(ChapterImage image, String field, double value)
            throws ReflectiveOperationException {
        Field f = ChapterImage.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setDouble(image, value);
    }
}
