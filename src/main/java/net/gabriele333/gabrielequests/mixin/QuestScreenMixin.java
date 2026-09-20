package net.gabriele333.gabrielequests.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ftb.mods.ftblibrary.ui.Button;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.quest.Movable;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.gabriele333.gabrielequests.client.BoxSelectState;
import net.gabriele333.gabrielequests.client.ShapeMenu;
import net.gabriele333.gabrielequests.client.TransformMenu;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Two things live here:
 *
 * <p><b>1. Shift + left-drag box selection.</b> {@code QuestScreen#drawBackground} decides
 * every frame what to do with the "grabbed" mouse button. Up to FTB Quests 2101.1.35:
 * <pre>
 *     if (grabbed.isLeft()) {        // pan the view
 *         ...
 *     } else if (grabbed.isMiddle()) // draw the rubber-band selection box
 *         ...
 *     }
 * </pre>
 * and from 2101.1.36, with the branches swapped and the box test extracted into the panel
 * (where FTB also added its own Alt + left-drag box selection):
 * <pre>
 *     if (questPanel.isDraggingSelectionBox()) { // draw the rubber-band selection box
 *         ...
 *     } else if (grabbed.isLeft()) {             // pan the view
 *         ...
 *     }
 * </pre>
 * While a Shift + left-drag box selection is active ({@link BoxSelectState#active}) we make
 * the pan predicate report {@code false} and the box predicate report {@code true} - reusing
 * FTB Quests' own box rendering instead of duplicating it. Each injector lists both shapes
 * of its predicate so one jar or the other matches.
 *
 * <p><b>2. "Set shape" on the whole selection.</b> We append an item to the object
 * context menu ({@code addObjectMenuItems}) that applies a chosen quest shape to every
 * selected quest at once - see {@link ShapeMenu}.
 */
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen")
public abstract class QuestScreenMixin {

    @Shadow
    @Final
    List<Movable> selectedObjects;

    @ModifyExpressionValue(
            method = "drawBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/ui/input/MouseButton;isLeft()Z",
                    ordinal = 0
            )
    )
    private boolean queststools$suppressPanWhileBoxSelecting(boolean original) {
        return original && !BoxSelectState.active;
    }

    /**
     * Draw the rubber band while our box selection runs. Two call sites for the same
     * reason as {@code QuestPanelMixin#queststools$selectBoxOnRelease}: 2101.1.36 replaced
     * the inlined {@code grabbed.isMiddle()} with {@code questPanel.isDraggingSelectionBox()}
     * and swapped the branches, so the box is now tested <em>before</em> the pan rather
     * than after. That reordering costs us nothing - each injector still modifies its own
     * predicate - but the missing {@code isMiddle} call killed the boot.
     */
    @ModifyExpressionValue(
            method = "drawBackground",
            require = 1,
            at = {
                    @At(
                            value = "INVOKE",
                            target = "Ldev/ftb/mods/ftblibrary/ui/input/MouseButton;isMiddle()Z",
                            ordinal = 0
                    ),
                    @At(
                            value = "INVOKE",
                            target = "Ldev/ftb/mods/ftbquests/client/gui/quests/QuestPanel;isDraggingSelectionBox()Z",
                            ordinal = 0
                    )
            }
    )
    private boolean queststools$drawBoxWhileBoxSelecting(boolean original) {
        return original || BoxSelectState.active;
    }

    @Inject(method = "addObjectMenuItems", at = @At("TAIL"))
    private void queststools$addSetShapeItem(List<ContextMenuItem> contextMenu, Button button, Runnable gui, QuestObjectBase object, Movable deletionFocus, CallbackInfo ci) {
        ShapeMenu.insertShapeItem(contextMenu, ShapeMenu.resolveTargets(selectedObjects, object));
        TransformMenu.insertTransformItems(contextMenu, TransformMenu.resolveTargets(selectedObjects, object));
    }
}
