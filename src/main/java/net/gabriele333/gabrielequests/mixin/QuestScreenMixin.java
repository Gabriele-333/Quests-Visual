package net.gabriele333.gabrielequests.mixin;

import dev.ftb.mods.ftblibrary.ui.Button;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.quest.Movable;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
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
 * <b>"Set shape" and the transforms on the whole selection.</b> We append items to the
 * object context menu ({@code addObjectMenuItems}) that apply a chosen quest shape, or a
 * rotation/flip, to every selected quest at once - see {@link ShapeMenu} and
 * {@link TransformMenu}.
 *
 * <p><b>Gone since 1.0.41: the Shift + left-drag box selection.</b> Two
 * {@code @ModifyExpressionValue}s used to sit on {@code drawBackground}, flipping the pan
 * predicate off and the selection-box predicate on while our gesture ran, so that FTB's own
 * rubber-band rendering did the drawing. FTB Quests 2101.1.36 ships an equivalent
 * Alt + left-drag gesture of its own, so the duplicate binding went away along with the two
 * injectors - which had just had to be re-targeted because that same release moved the
 * predicate into {@code QuestPanel#isDraggingSelectionBox()} and swapped the branches.
 */
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen")
public abstract class QuestScreenMixin {

    @Shadow
    @Final
    List<Movable> selectedObjects;

    @Inject(method = "addObjectMenuItems", at = @At("TAIL"))
    private void queststools$addSetShapeItem(List<ContextMenuItem> contextMenu, Button button, Runnable gui, QuestObjectBase object, Movable deletionFocus, CallbackInfo ci) {
        ShapeMenu.insertShapeItem(contextMenu, ShapeMenu.resolveTargets(selectedObjects, object));
        TransformMenu.insertTransformItems(contextMenu, TransformMenu.resolveTargets(selectedObjects, object));
    }
}
