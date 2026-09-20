package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ItemStackConfig;
import dev.ftb.mods.ftblibrary.config.ui.resource.SelectItemStackScreen;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.minecraft.world.item.ItemStack;

/**
 * "Display item" = a decorative {@link ChapterImage} whose icon is an {@link ItemIcon},
 * so it renders an item using its real model (3D for blocks, the flat sprite for flat
 * items) instead of a static texture.
 *
 * <p>This deliberately piggybacks on FTB Quests' existing {@code ChapterImage}
 * infrastructure - the creation flow below mirrors FTB Quests' own "add image" action
 * ({@code QuestPanel#showImageCreationScreen}): build the object client-side with a
 * placeholder id of 0, then ask the server to create it with {@code CreateObjectMessage},
 * which assigns the real id, files it under its parent chapter and syncs it back. The only
 * difference is the icon is an item, not a texture.</p>
 *
 * <p>Because the image is serialised as {@code image.toString()} (an {@code item:<id>}
 * string) and restored via {@code Icon.getIcon(String)}, it round-trips through save/load
 * and networking for free. The render-state fix that item models require lives in
 * {@code ChapterImageButtonMixin}.</p>
 */
public final class DisplayItemCreator {

    private DisplayItemCreator() {
    }

    /**
     * Opens the item picker and, once an item is chosen, creates a display item at the
     * given quest-space coordinates in the currently selected chapter.
     */
    public static void open(QuestScreen screen, double x, double y) {
        Chapter chapter = screen.getSelectedChapter().orElse(null);
        if (chapter == null) {
            return;
        }

        ItemStackConfig config = new ItemStackConfig(false, false);
        new SelectItemStackScreen(config, accepted -> {
            if (accepted) {
                ItemStack stack = config.getValue();
                if (stack != null && !stack.isEmpty()) {
                    create(chapter, stack, x, y);
                }
            }
            // The picker is a full screen; return to the quest editor when it closes.
            screen.openGui();
        }).openGui();
    }

    private static void create(Chapter chapter, ItemStack stack, double x, double y) {
        ChapterImage image = new ChapterImage(0L, chapter);
        image.setImage(ItemIcon.getItemIcon(stack));
        image.setPosition(x, y);
        image.fixupAspectRatio(false);
        NetworkManager.sendToServer(CreateObjectMessage.requestCreation(image));
    }
}
