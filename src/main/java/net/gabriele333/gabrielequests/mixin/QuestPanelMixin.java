package net.gabriele333.gabrielequests.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.gabriele333.gabrielequests.client.BoxSelectState;
import net.gabriele333.gabrielequests.client.DisplayItemCreator;
import net.gabriele333.gabrielequests.client.DisplayOrbitDrag;
import net.gabriele333.gabrielequests.client.EntityCreator;
import net.gabriele333.gabrielequests.client.MannequinCreator;
import net.gabriele333.gabrielequests.client.MultiblockCreator;
import net.gabriele333.gabrielequests.client.PortalCreator;
import net.gabriele333.gabrielequests.client.StructureCreator;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Two editor additions live here:
 *
 * <p><b>1. Shift + left-drag box selection.</b> {@code QuestPanel#mousePressed} only stores
 * a "grabbed" button (and the anchor point used by the selection box) when the click lands
 * on empty space - clicks on a quest are consumed by the child widgets first. That is
 * exactly when we want to start a box selection, so we latch our intent right after the
 * {@code grabbed} field is written, if Shift is held and the button is the left one.
 * {@code mouseReleased} normally finalises the selection only for the middle button; we
 * extend that check so it also fires for our shift box selection.
 *
 * <p><b>2. "Add display item".</b> We append an entry next to the built-in "add image" one
 * in the empty-space right-click menu that creates a {@code ChapterImage} backed by an item
 * model - see {@link DisplayItemCreator}.
 */
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.QuestPanel")
public abstract class QuestPanelMixin {

    @Shadow
    @Final
    private QuestScreen questScreen;

    @Shadow
    protected double questX;

    @Shadow
    protected double questY;

    /**
     * Every fresh press starts from a clean slate. This runs before the press is
     * dispatched to child widgets, so it clears stale orbit-drag state without touching
     * the drag that {@code ChapterImageButtonMixin} may begin an instant later.
     */
    @Inject(method = "mousePressed", at = @At("HEAD"))
    private void queststools$resetBoxSelect(MouseButton button, CallbackInfoReturnable<Boolean> cir) {
        BoxSelectState.active = false;
        DisplayOrbitDrag.clear();
        if (button.isRight()) {
            // TEMPORARY diagnostic, pairs with the one in DisplayOrbitDrag#resetView: this
            // fires for every right press the quest panel sees, so a line here WITHOUT one
            // from resetView means FTB never routed the press to the image widget.
            DisplayOrbitDrag.logRightPress();
        }
    }

    /**
     * Fires right after {@code questScreen.grabbed = button}. Reaching this point means
     * the click was on empty space (child widgets did not consume it), so a Shift +
     * left press means "begin a box selection".
     */
    @Inject(
            method = "mousePressed",
            at = @At(
                    value = "FIELD",
                    target = "Ldev/ftb/mods/ftbquests/client/gui/quests/QuestScreen;grabbed:Ldev/ftb/mods/ftblibrary/ui/input/MouseButton;",
                    opcode = Opcodes.PUTFIELD,
                    shift = At.Shift.AFTER
            )
    )
    private void queststools$maybeStartBoxSelect(MouseButton button, CallbackInfoReturnable<Boolean> cir) {
        if (button.isLeft() && Screen.hasShiftDown()) {
            BoxSelectState.active = true;
        }
    }

    /**
     * FTB Quests runs {@code selectAllQuestsInBox(...)} on release when the drag was a
     * selection box. Report {@code true} here as well when our shift box selection is
     * active so the same selection logic runs for Shift + left-drag.
     *
     * <p><b>Two shapes, because FTB moved this check.</b> Up to 2101.1.35 the test was
     * inlined here as {@code grabbed.isMiddle()}. 2101.1.36 extracted it into
     * {@code QuestPanel#isDraggingSelectionBox()} - which also gained FTB's own
     * Alt + left-drag box selection - so the old call site is simply gone from this
     * method and an injector that only knows {@code isMiddle} fails to apply, taking the
     * whole game down with "Critical injection failure" at boot. Both call sites are
     * listed so one jar or the other matches; {@code require = 1} keeps that honest, so
     * if a future release renames the predicate again we get the loud failure rather than
     * a silently dead feature.
     */
    @ModifyExpressionValue(
            method = "mouseReleased",
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
    private boolean queststools$selectBoxOnRelease(boolean original) {
        return original || BoxSelectState.active;
    }

    /** Clear the latch once the drag is over; also finalise a multiblock orbit drag. */
    @Inject(method = "mouseReleased", at = @At("TAIL"))
    private void queststools$clearBoxSelect(MouseButton button, CallbackInfo ci) {
        BoxSelectState.active = false;
        DisplayOrbitDrag.commit();
    }

    /**
     * Append "Add display item" and "Add display multiblock" to the empty-space
     * right-click menu, just before FTB Quests opens it. {@code questX}/{@code questY}
     * hold the quest-space coordinates under the cursor (same values the built-in "add
     * image" action uses) and are captured now so the menu callback places the object
     * where the menu was opened. The multiblock entry only appears when Mekanism's
     * blocks are actually registered.
     */
    @Inject(
            method = "mousePressed",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbquests/client/gui/quests/QuestScreen;openContextMenu(Ljava/util/List;)Ldev/ftb/mods/ftblibrary/ui/ContextMenu;"
            )
    )
    private void queststools$addDisplayItemEntry(MouseButton button, CallbackInfoReturnable<Boolean> cir, @Local List<ContextMenuItem> contextMenu) {
        double x = questX;
        double y = questY;
        contextMenu.add(new ContextMenuItem(
                Component.translatable("gabrielequests.add_display_item"),
                Icons.ADD,
                b -> DisplayItemCreator.open(questScreen, x, y)
        ));
        if (MultiblockCreator.isAvailable()) {
            contextMenu.add(new ContextMenuItem(
                    Component.translatable("gabrielequests.add_display_multiblock"),
                    Icons.ADD,
                    b -> MultiblockCreator.open(questScreen, x, y)
            ));
        }
        contextMenu.add(new ContextMenuItem(
                Component.translatable("gabrielequests.add_display_mannequin"),
                Icons.ADD,
                b -> MannequinCreator.open(questScreen, x, y)
        ));
        contextMenu.add(new ContextMenuItem(
                Component.translatable("gabrielequests.add_display_entity"),
                Icons.ADD,
                b -> EntityCreator.open(questScreen, x, y)
        ));
        if (StructureCreator.isAvailable()) {
            contextMenu.add(new ContextMenuItem(
                    Component.translatable("gabrielequests.add_display_structure"),
                    Icons.ADD,
                    b -> StructureCreator.open(questScreen, x, y)
            ));
        }
        if (PortalCreator.isAvailable()) {
            contextMenu.add(new ContextMenuItem(
                    Component.translatable("gabrielequests.add_display_portal"),
                    Icons.ADD,
                    b -> PortalCreator.open(questScreen, x, y)
            ));
        }
    }
}
