package net.gabriele333.gabrielequests.mixin;

import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.Movable;
import net.gabriele333.gabrielequests.client.ShapeMenu;
import net.gabriele333.gabrielequests.client.TransformMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/**
 * When several quests are selected, {@code QuestButton#onClicked} builds a dedicated
 * "multi-selection" context menu inline (with "clear rewards", "bulk change size",
 * "delete", ...) and opens it via {@code BaseScreen#openContextMenu(List)} - it does NOT go
 * through {@code QuestScreen#addObjectMenuItems}. That is why our "Set shape" entry (added
 * in {@code QuestScreenMixin} to the single-object menu) never showed up for a selection.
 *
 * <p>Here we grab the list argument passed to that {@code openContextMenu(List)} call and
 * append "Set shape (N quests)" so the whole selection can be reshaped at once.</p>
 */
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.QuestButton")
public abstract class QuestButtonMixin {

    @Shadow
    @Final
    protected QuestScreen questScreen;

    @ModifyArg(
            method = "onClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/ui/BaseScreen;openContextMenu(Ljava/util/List;)Ldev/ftb/mods/ftblibrary/ui/ContextMenu;"
            ),
            index = 0
    )
    private List<ContextMenuItem> queststools$addBulkShapeItem(List<ContextMenuItem> menu) {
        List<Movable> selected = ((QuestScreenAccessor) (Object) questScreen).queststools$selectedObjects();
        ShapeMenu.insertShapeItem(menu, ShapeMenu.resolveTargets(selected, null));
        TransformMenu.insertTransformItems(menu, TransformMenu.resolveTargets(selected, null));
        return menu;
    }
}
