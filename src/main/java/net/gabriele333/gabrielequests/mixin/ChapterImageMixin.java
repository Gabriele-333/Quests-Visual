package net.gabriele333.gabrielequests.mixin;

import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.ItemStackConfig;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.EntityCreator;
import net.gabriele333.gabrielequests.client.MannequinCreator;
import net.gabriele333.gabrielequests.client.MultiblockCreator;
import net.gabriele333.gabrielequests.client.PortalCreator;
import net.gabriele333.gabrielequests.client.StructureCreator;
import net.gabriele333.gabrielequests.client.entity.EntityIcon;
import net.gabriele333.gabrielequests.client.mannequin.MannequinIcon;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockIcon;
import net.gabriele333.gabrielequests.client.portal.PortalIcon;
import net.gabriele333.gabrielequests.client.structure.StructureIcon;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes for chapter images whose icon is an item model (our "display item" feature).
 *
 * <p>Both injections detect the item case via {@code Icon#getIngredient()}, which returns
 * the {@link ItemStack} both for a freshly created {@code ItemIcon} and for the
 * {@code LazyIcon} wrapper that {@code Icon.getIcon("item:...")} produces after a
 * save/reload (it delegates to the resolved icon), so they cover both lifecycles.</p>
 *
 * <p><b>{@code getAltTitle}:</b> vanilla derives it from {@code image.toString()}, and
 * {@code ItemIcon#toString()} serialises the item's <em>entire data component map</em>
 * ({@code item:<id> <count> <damage> <nbt...>}). That string is used as the title row of
 * the right-click context menu ({@code ChapterImageButton#onClicked}), and since the menu
 * sizes itself on its widest row, the menu became wider than the screen and the actual
 * entries were unusable. We return the item's display name instead. Note we must NOT touch
 * {@code ItemIcon#toString()} itself - that string IS the serialised form of the image.
 * (FTB Quests 2101.1.28 turned {@code ChapterImage} into a {@code QuestObjectBase}, where
 * {@code getTitle()} is final and falls back to the overridable {@code getAltTitle()} when
 * the object has no explicit title - that fallback is the hook we take over now.)</p>
 *
 * <p><b>{@code fillConfigGroup}:</b> vanilla registers the "image" property as an
 * {@link dev.ftb.mods.ftblibrary.config.ImageResourceConfig} (a texture
 * {@code ResourceLocation}) with setter {@code v -> setImage(Icon.getIcon(v))}. On accept,
 * {@code ConfigGroup#save(true)} applies <em>every</em> setter unconditionally - so editing
 * any property (width, height, ...) also re-applied the image one, replacing the item icon
 * with a texture icon for a texture that doesn't exist ({@code ItemIcon} reports the item's
 * registry id as its "resource location"; after a reload the {@code LazyIcon} isn't an
 * {@code IResourceIcon} at all and yields {@code ftblibrary:none}). Result: the display
 * item vanished as soon as the properties screen was accepted. Re-adding a config value
 * under the same id replaces the entry in the group's backing {@code LinkedHashMap}
 * (keeping its position in the GUI), so here we swap it for an {@link ItemStackConfig}
 * whose setter rebuilds a proper {@code ItemIcon} - resizing now works, and as a bonus the
 * "image" row becomes an item picker to change the displayed item.</p>
 */
@Mixin(ChapterImage.class)
public abstract class ChapterImageMixin {

    @Inject(method = "getAltTitle", at = @At("HEAD"), cancellable = true)
    private void queststools$useItemNameAsTitle(CallbackInfoReturnable<Component> cir) {
        ChapterImage self = (ChapterImage) (Object) this;
        if (self.getImage().getIngredient() instanceof ItemStack stack && !stack.isEmpty()) {
            cir.setReturnValue(stack.getHoverName());
        } else if (self.getImage() instanceof MultiblockIcon multiblock) {
            cir.setReturnValue(multiblock.displayName());
        } else if (self.getImage() instanceof MannequinIcon mannequin) {
            cir.setReturnValue(mannequin.displayName());
        } else if (self.getImage() instanceof EntityIcon entity) {
            cir.setReturnValue(entity.displayName());
        } else if (self.getImage() instanceof StructureIcon structure) {
            cir.setReturnValue(structure.displayName());
        } else if (self.getImage() instanceof PortalIcon portal) {
            cir.setReturnValue(portal.displayName());
        }
    }

    @Inject(method = "fillConfigGroup", at = @At("TAIL"))
    private void queststools$replaceImageConfigForItems(ConfigGroup config, CallbackInfo ci) {
        ChapterImage self = (ChapterImage) (Object) this;
        if (self.getImage().getIngredient() instanceof ItemStack stack && !stack.isEmpty()) {
            config.add("image", new ItemStackConfig(false, false), stack.copy(),
                    s -> self.setImage(ItemIcon.getItemIcon(s)), ItemStack.EMPTY);
        } else if (self.getImage() instanceof MultiblockIcon multiblock && multiblock.spec() != null) {
            // Same LinkedHashMap trick, multiblock flavour: the "image" row becomes the
            // multiblock type selector and the size/glass rows are appended after it.
            // Every setter re-normalises the spec, so edits are validated on save.
            MultiblockCreator.addSpecConfigs(config, "image", multiblock.spec(),
                    spec -> self.setImage(new MultiblockIcon(spec)));
        } else if (self.getImage() instanceof MannequinIcon mannequin && mannequin.spec() != null) {
            // Same trick, mannequin flavour: the "image" row becomes the helmet picker and
            // the other equipment rows follow it; every setter rebuilds the icon.
            MannequinCreator.addSpecConfigs(config, "image", mannequin.spec(),
                    spec -> self.setImage(new MannequinIcon(spec)));
        } else if (self.getImage() instanceof EntityIcon entity && entity.spec() != null) {
            // Same trick, entity flavour: the "image" row becomes the entity-type picker;
            // the setter rebuilds the icon.
            EntityCreator.addSpecConfigs(config, "image", entity.spec(),
                    spec -> self.setImage(new EntityIcon(spec)));
        } else if (self.getImage() instanceof StructureIcon structure && structure.spec() != null) {
            // Same trick, structure flavour: the "image" row becomes the structure picker
            // (scoped to the structure's own mod, with a mod row to switch); the setter
            // rebuilds the icon.
            StructureCreator.addSpecConfigs(config, "image", structure.spec(),
                    spec -> self.setImage(new StructureIcon(spec)));
        } else if (self.getImage() instanceof PortalIcon portal && portal.spec() != null) {
            // Same trick, portal flavour: the "image" row becomes the portal picker and the
            // frame/shape/size rows follow it; the setter rebuilds the icon.
            PortalCreator.addSpecConfigs(config, "image", portal.spec(),
                    spec -> self.setImage(new PortalIcon(spec)));
        }
    }
}
