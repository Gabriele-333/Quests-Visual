package net.gabriele333.gabrielequests.client;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.net.EditObjectMessage;
import dev.ftb.mods.ftbquests.quest.Movable;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.QuestShape;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.gabriele333.gabrielequests.mixin.QuestAccessor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the "Set shape" context-menu entry that lets you change the shape of every
 * selected quest at once (or of a single right-clicked quest when nothing is selected).
 *
 * <p>The shapes come from FTB Quests' own registry ({@link QuestShape#idMap}) plus an
 * explicit "Default (inherit)" entry that stores an empty string (see {@link #applyShape}).</p>
 *
 * <p><b>Why this is NOT a {@link ContextMenuItem#subMenu} anymore:</b> FTB Library's
 * stacked sub-menu positions itself with {@code button.getPosX()} (coordinates relative
 * to the parent menu) but applies them as screen coordinates, so the sub-menu opens in
 * the top-left corner of the screen instead of next to the clicked entry - it looks like
 * the click did nothing. FTB Quests itself never uses {@code subMenu}; its own
 * "properties" entry ({@code QuestScreen#openPropertiesSubMenu}) closes the current menu
 * and opens a <em>new</em> context menu at the mouse position. We follow that exact
 * pattern here.</p>
 */
public final class ShapeMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Translation key of FTB Quests' "Change Size for all..." multi-selection entry. */
    private static final String BULK_SIZE_KEY = "ftbquests.gui.bulk_change_size";

    private ShapeMenu() {
    }

    /**
     * The quests a shape change should apply to: every selected quest, or - if nothing is
     * selected - the right-clicked object when it is itself a quest.
     */
    public static List<Quest> resolveTargets(List<Movable> selectedObjects, QuestObjectBase clicked) {
        List<Quest> targets = new ArrayList<>();
        for (Movable movable : selectedObjects) {
            if (movable instanceof Quest quest) {
                targets.add(quest);
            }
        }
        if (targets.isEmpty() && clicked instanceof Quest quest) {
            targets.add(quest);
        }
        return targets;
    }

    /**
     * Adds the "Set shape" entry to a context menu, positioned right after FTB Quests'
     * "Change Size for all..." item when present (otherwise appended). No-op if there are
     * no quest targets.
     */
    public static void insertShapeItem(List<ContextMenuItem> menu, List<Quest> targets) {
        ContextMenuItem item = buildShapeItem(targets);
        if (item == null) {
            return;
        }
        int insertAt = -1;
        for (int i = 0; i < menu.size(); i++) {
            if (menu.get(i).getTitle().getContents() instanceof TranslatableContents tc
                    && BULK_SIZE_KEY.equals(tc.getKey())) {
                insertAt = i + 1;
                break;
            }
        }
        if (insertAt >= 0 && insertAt <= menu.size()) {
            menu.add(insertAt, item);
        } else {
            menu.add(item);
        }
    }

    /**
     * Builds the "Set shape" menu item for the given targets, or {@code null} if there is
     * nothing to apply it to (e.g. the right-clicked object is not a quest). Clicking it
     * closes the current context menu and opens the shape-selection menu at the mouse.
     */
    public static ContextMenuItem buildShapeItem(List<Quest> targets) {
        if (targets.isEmpty()) {
            return null;
        }

        Component title = targets.size() > 1
                ? Component.translatable("gabrielequests.set_shape.multi", targets.size())
                : Component.translatable("gabrielequests.set_shape");

        return new ContextMenuItem(
                title,
                QuestShape.get(targets.get(0).getShape()),
                button -> openShapeSelectionMenu(button.getGui(), title, targets)
        );
    }

    /**
     * Opens a fresh context menu listing every available shape - same flow as FTB Quests'
     * own {@code QuestScreen#openPropertiesSubMenu} (header + separator + entries).
     */
    private static void openShapeSelectionMenu(BaseScreen gui, Component title, List<Quest> targets) {
        List<ContextMenuItem> menu = new ArrayList<>();
        menu.add(ContextMenuItem.title(title));
        menu.add(ContextMenuItem.SEPARATOR);

        // "Default" must be stored as an EMPTY string: Quest#getShape() treats "" as
        // "inherit the chapter/file default". Storing the literal "default" would fall
        // through QuestShape#get to an arbitrary fallback shape.
        menu.add(new ContextMenuItem(
                Component.translatable("gabrielequests.set_shape.default"),
                QuestShape.get(""),
                button -> applyShape(targets, "")
        ));
        for (String id : QuestShape.idMap.keys) {
            menu.add(new ContextMenuItem(
                    QuestShape.idMap.getDisplayName(id),
                    QuestShape.get(id),
                    button -> applyShape(targets, id)
            ));
        }

        gui.openContextMenu(menu);
    }

    private static void applyShape(List<Quest> targets, String shapeValue) {
        LOGGER.info("[GabrieleQuests/QuestsTools] applyShape('{}') to {} quest(s)", shapeValue, targets.size());
        try {
            for (Quest quest : targets) {
                ((QuestAccessor) (Object) quest).queststools$setShape(shapeValue);
                EditObjectMessage.sendToServer(quest);
                LOGGER.info("[GabrieleQuests/QuestsTools]   quest {} -> getShape() now returns '{}'",
                        Long.toHexString(quest.id), quest.getShape());
            }
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] applyShape FAILED", t);
        }
    }
}
