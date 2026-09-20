package net.gabriele333.gabrielequests.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.ui.GuiHelper;
import dev.ftb.mods.ftblibrary.ui.Theme;
import dev.ftb.mods.ftblibrary.ui.Widget;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.DisplayOrbitDrag;
import net.gabriele333.gabrielequests.client.OrbitDraggable;
import net.gabriele333.gabrielequests.client.entity.EntityIcon;
import net.gabriele333.gabrielequests.client.mannequin.MannequinIcon;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockIcon;
import net.gabriele333.gabrielequests.client.portal.PortalIcon;
import net.gabriele333.gabrielequests.client.structure.StructureIcon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes for the widget that renders a chapter image, all in service of our "display
 * item" feature (a {@code ChapterImage} whose icon is an {@code ItemIcon}).
 *
 * <p><b>Render-state leakage ({@code draw}):</b> vanilla item rendering
 * ({@code GuiGraphics#renderItem}, reached via {@code ItemIcon#draw} →
 * {@code GuiHelper#drawItem}) flushes its batch through
 * {@code entity_solid}/{@code entity_cutout} render types whose teardown leaves
 * <b>blending disabled</b>. FTB Library's textured drawing
 * ({@code ImageIcon#draw}/{@code GuiHelper#drawTexturedRect}) never re-enables blend and
 * just trusts the current state, and {@code QuestPanel} adds image widgets <em>before</em>
 * quest widgets - so the first quest drawn right after a display item rendered its shape
 * with blend off: the transparent pixels of the shape/outline textures became opaque
 * white (the "white outline" artifact). Injecting right after the {@code Icon#draw}
 * call restores the standard widget-drawing state via
 * {@link GuiHelper#setupDrawing()} - blend enabled, default blend func, white shader
 * color - which is exactly what FTB widgets expect.
 *
 * <p><b>Readable names ({@code onClicked}):</b> the delete confirmation builds its text
 * from {@code chapterImage.getImage().toString()}, which for an item icon serialises the
 * whole data component map - a huge string. The context menu title itself goes through
 * {@code ChapterImage#getTitle()} (fixed in {@link ChapterImageMixin}); here we cover the
 * remaining spot by substituting the item's display name. (Up to FTB Quests 2101.1.27 the
 * properties-screen title needed the same treatment in {@code openEditScreen}; that method
 * is gone in 2101.1.28 - editing now goes through {@code QuestObjectBase#onEditButtonClicked},
 * whose title is the generic "&lt;screen&gt; [image]" and never contains the icon string.)</p>
 */
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.ChapterImageButton")
public abstract class ChapterImageButtonMixin {

    @Shadow
    @Final
    private ChapterImage chapterImage;

    /**
     * True for the six icon kinds this mod adds (display item / multiblock / mannequin /
     * entity / structure / portal).
     */
    private boolean queststools$isOurDisplay() {
        Icon icon = chapterImage.getImage();
        return icon instanceof MultiblockIcon || icon instanceof MannequinIcon || icon instanceof EntityIcon
                || icon instanceof StructureIcon || icon instanceof PortalIcon
                || (icon.getIngredient() instanceof ItemStack stack && !stack.isEmpty());
    }

    /**
     * <b>No hover tooltip on our displays.</b> Up to 2101.1.27 the widget was built with an
     * empty title and only showed the hover text the author had typed. 2101.1.28 builds it
     * with {@code chapterImage.getTitle()} and {@code addMouseOverText} prints that, so every
     * image acquired a tooltip - on ours, the name we synthesise in {@link ChapterImageMixin}
     * ("Mannequin", the multiblock's name, the item's name...). These are decorations, not
     * things to label, so we drop the tooltip for them; the name still identifies the object
     * in the right-click menu, which is where it is actually useful. Plain FTB images keep
     * their tooltip.
     */
    @Inject(method = "addMouseOverText", at = @At("HEAD"), cancellable = true)
    private void queststools$hideDisplayTooltip(TooltipList list, CallbackInfo ci) {
        if (queststools$isOurDisplay()) {
            ci.cancel();
        }
    }

    /**
     * Make a view-rotatable display multiblock mouse-interactive for non-editors too.
     * {@code checkMouseOver} bails early for an image with no click action unless
     * {@code file.canEdit()} - so in play mode the widget is inert and our drag never
     * starts. Reporting {@code canEdit()} as true here (only for our viewRotate case)
     * lets the method fall through to its normal geometric bounds test, which still
     * decides whether the mouse is actually over the image. The other early-out guards
     * (hovering a quest, moving objects, open panels) are evaluated separately and stay
     * intact.
     */
    @ModifyExpressionValue(
            method = "checkMouseOver",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbquests/client/ClientQuestFile;canEdit()Z"
            )
    )
    private boolean queststools$viewRotateIsInteractive(boolean original) {
        return original || (chapterImage.getImage() instanceof OrbitDraggable d
                && d.orbitEnabled() && d.orbitViewRotate());
    }

    @Inject(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/icon/Icon;draw(Lnet/minecraft/client/gui/GuiGraphics;IIII)V",
                    shift = At.Shift.AFTER
            )
    )
    private void queststools$restoreRenderState(GuiGraphics graphics, Theme theme, int x, int y, int w, int h, CallbackInfo ci) {
        GuiHelper.setupDrawing();
        // The icon just drew; drop the render context so it never leaks to another widget.
        DisplayOrbitDrag.clearRenderContext();
    }

    /**
     * <b>Diffuse lighting of item icons.</b> {@code draw} renders the icon into a 1x1 (or
     * 2x2) box blown up by {@code pose.scale(w, h, 1)} - the X/Y axes get the image size
     * in pixels while <b>Z keeps a scale of 1</b>. That is harmless for flat textures, but
     * {@link com.mojang.blaze3d.vertex.PoseStack#scale} folds the inverse of a non-uniform
     * scale into the normal matrix, so every normal of a 3D item model gets its Z
     * component amplified by a factor of w: normals that should point up end up pointing
     * at the camera, the dot product with the two GUI diffuse lights (both dominated by
     * +Y) collapses and the block renders <b>much darker</b> than the same item in the
     * inventory. Only block-shaped models suffer - flat items are drawn with
     * {@code Lighting.setupForFlatItems} and a normal of (0,0,1), which the squash leaves
     * alone.
     *
     * <p>The geometry itself is fine (an orthographic projection ignores the Z scale), so
     * the fix is to touch only the normals: re-normalising the columns of the normal
     * matrix strips exactly the anisotropic part and leaves the rotation, i.e. what the
     * pose would carry with a uniform scale. Everything vanilla does afterwards (the
     * {@code (1,-1,1)} flip is orthonormal, the {@code x16} and the model transform are
     * uniform) preserves it, so the item ends up lit exactly like an inventory slot.
     * FTB's own {@code popPose} at the end of {@code draw} undoes this.</p>
     *
     * <p>Skipped for our own 3D icons: mannequins/entities already cancel FTB's scaling on
     * the pose itself ({@code MannequinRenderer#renderEntity}), and the multiblock draws a
     * pre-rendered texture.</p>
     */
    @Inject(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V",
                    // Only the scale that frames the icon. Since 2101.1.28 draw() has a
                    // second one, for the optional text drawn over the image, whose normals
                    // are none of our business.
                    ordinal = 0,
                    shift = At.Shift.AFTER
            )
    )
    private void queststools$unsquashIconNormals(GuiGraphics graphics, Theme theme, int x, int y, int w, int h, CallbackInfo ci) {
        if (chapterImage.getImage() instanceof OrbitDraggable) {
            return;
        }
        Matrix3f normal = graphics.pose().last().normal();
        Vector3f[] columns = {new Vector3f(), new Vector3f(), new Vector3f()};
        for (int i = 0; i < 3; i++) {
            normal.getColumn(i, columns[i]);
            float length = columns[i].length();
            if (!(length > 1.0E-6F)) { // also rejects NaN: degenerate pose, leave it alone
                return;
            }
            columns[i].div(length);
        }
        for (int i = 0; i < 3; i++) {
            normal.setColumn(i, columns[i]);
        }
    }

    /**
     * "Hold left to orbit" a display multiblock. Always available in edit mode (where it
     * persists); also for non-editors when the author ticked "allow view rotation" on the
     * multiblock (a view-only drag, held client-side). A plain left press on an image with
     * no click action is exactly the case FTB does not consume - it would fall through to
     * map panning - so taking it over conflicts with nothing: Alt+click (move image), click
     * actions and the right-click menu all return {@code true} above and never reach this.
     * Shift/Ctrl are left alone for selection gestures.
     */
    @Inject(method = "mousePressed", at = @At("RETURN"), cancellable = true)
    private void queststools$startOrbitDrag(MouseButton button, CallbackInfoReturnable<Boolean> cir) {
        Widget self = (Widget) (Object) this;
        if (!cir.getReturnValueZ() && button.isLeft() && self.isMouseOver()
                && !Screen.hasShiftDown() && !Screen.hasControlDown() && !Screen.hasAltDown()
                && chapterImage.getImage() instanceof OrbitDraggable draggable && draggable.orbitEnabled()) {
            boolean canEdit = chapterImage.getChapter().getQuestFile().canEdit();
            if (canEdit || draggable.orbitViewRotate()) {
                DisplayOrbitDrag.begin(chapterImage, draggable, self.getMouseX(), self.getMouseY(), canEdit);
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * <b>Right-click a display = back to the author's orientation.</b> A non-editor's orbit
     * drag is view-only and merely held client-side (see {@link DisplayOrbitDrag}), so there
     * has to be a way to undo it; right-click is free here, because {@code onClicked} only
     * does something with the right button when {@code canEdit()} (it opens the editor's
     * context menu) and otherwise simply returns.
     *
     * <p>No {@code canEdit()} check: {@link DisplayOrbitDrag#resetView} is a no-op unless the
     * image has a held view angle, and an editor's drag persists into the icon instead of
     * being held - so the editor's context menu is unaffected either way, and the reset cannot
     * be silently skipped because FTB considers the player an editor at that moment.</p>
     */
    @Inject(method = "onClicked", at = @At("HEAD"))
    private void queststools$resetViewRotation(MouseButton button, CallbackInfo ci) {
        if (button.isRight() && chapterImage.getImage() instanceof OrbitDraggable) {
            DisplayOrbitDrag.resetView(chapterImage);
        }
    }

    /**
     * Before the icon draws, tell the drag state which image this is (so a held/dragged
     * view angle routes to the right multiblock) and, if this image is being dragged, feed
     * it the current mouse position.
     */
    @Inject(method = "draw", at = @At("HEAD"))
    private void queststools$updateOrbitDrag(GuiGraphics graphics, Theme theme, int x, int y, int w, int h, CallbackInfo ci) {
        DisplayOrbitDrag.setRenderContext(chapterImage);
        if (DisplayOrbitDrag.isDraggingImage(chapterImage)) {
            Widget self = (Widget) (Object) this;
            DisplayOrbitDrag.update(self.getMouseX(), self.getMouseY());
        }
    }

    // Rebuilds the delete-confirmation text ("delete_item" with the icon's toString()
    // as argument) with the item's/multiblock's display name instead.
    @ModifyArg(
            method = "onClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/ui/ContextMenuItem;setYesNoText(Lnet/minecraft/network/chat/Component;)Ldev/ftb/mods/ftblibrary/ui/ContextMenuItem;"
            ),
            index = 0
    )
    private Component queststools$itemNameInDeleteConfirm(Component text) {
        if (chapterImage.getImage().getIngredient() instanceof ItemStack stack && !stack.isEmpty()) {
            return Component.translatable("delete_item", stack.getHoverName());
        }
        if (chapterImage.getImage() instanceof MultiblockIcon multiblock) {
            return Component.translatable("delete_item", multiblock.displayName());
        }
        if (chapterImage.getImage() instanceof MannequinIcon mannequin) {
            return Component.translatable("delete_item", mannequin.displayName());
        }
        if (chapterImage.getImage() instanceof EntityIcon entity) {
            return Component.translatable("delete_item", entity.displayName());
        }
        if (chapterImage.getImage() instanceof StructureIcon structure) {
            return Component.translatable("delete_item", structure.displayName());
        }
        if (chapterImage.getImage() instanceof PortalIcon portal) {
            return Component.translatable("delete_item", portal.displayName());
        }
        return text;
    }
}
