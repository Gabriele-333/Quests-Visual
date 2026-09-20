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
 * every frame what to do with the "grabbed" mouse button:
 * <pre>
 *     if (grabbed.isLeft()) {        // pan the view
 *         ...
 *     } else if (grabbed.isMiddle()) // draw the rubber-band selection box
 *         ...
 *     }
 * </pre>
 * While a Shift + left-drag box selection is active ({@link BoxSelectState#active}) we make
 * {@code isLeft()} report {@code false} (no pan) and {@code isMiddle()} report {@code true}
 * (draw the box) - reusing FTB Quests' own box rendering instead of duplicating it.
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

    @ModifyExpressionValue(
            method = "drawBackground",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/ui/input/MouseButton;isMiddle()Z",
                    ordinal = 0
            )
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
