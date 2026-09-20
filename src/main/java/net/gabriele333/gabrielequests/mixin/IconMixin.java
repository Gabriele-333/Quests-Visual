package net.gabriele333.gabrielequests.mixin;

import dev.ftb.mods.ftblibrary.icon.Icon;
import net.gabriele333.gabrielequests.client.entity.EntityIcon;
import net.gabriele333.gabrielequests.client.entity.EntitySpec;
import net.gabriele333.gabrielequests.client.mannequin.MannequinIcon;
import net.gabriele333.gabrielequests.client.mannequin.MannequinSpec;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockIcon;
import net.gabriele333.gabrielequests.client.multiblock.MultiblockSpec;
import net.gabriele333.gabrielequests.client.portal.PortalIcon;
import net.gabriele333.gabrielequests.client.portal.PortalSpec;
import net.gabriele333.gabrielequests.client.structure.StructureIcon;
import net.gabriele333.gabrielequests.client.structure.StructureSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deserialisation hook for the "display multiblock" feature. {@code ChapterImage}
 * persists its icon as {@code image.toString()} and rebuilds it with
 * {@code Icon.getIcon(String)}, whose parser has no extension registry - so we intercept
 * our {@code gabrielequests-multiblock:} prefix at HEAD, before the parser splits the string on
 * {@code " + "} (combined icons) and {@code ";"} (icon properties).
 *
 * <p>This mixin is registered in the <b>common</b> list, not the client one: quest
 * chapters are read and re-serialised on the server as well, and if the server parsed our
 * string into some fallback icon, the next save would overwrite it with that fallback's
 * {@code toString()}, destroying the multiblock. {@link MultiblockIcon} is deliberately
 * common-safe (GL/GUI code is only reached through {@code draw}, never called on a
 * server) and preserves unparseable strings verbatim.</p>
 */
@Mixin(Icon.class)
public abstract class IconMixin {

    @Inject(
            method = "getIcon(Ljava/lang/String;)Ldev/ftb/mods/ftblibrary/icon/Icon;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void queststools$parseMultiblockIcon(String id, CallbackInfoReturnable<Icon> cir) {
        if (id == null) {
            return;
        }
        // Both the current prefix and the legacy fmtt2- one (content authored by old FMTT2):
        // intercepting here is what keeps the legacy string away from FTB's own parser, which
        // would throw a ResourceLocationException on it (invalid path). MultiblockIcon /
        // MannequinIcon re-serialise with the new prefix, so a re-save migrates the string.
        if (id.startsWith(MultiblockSpec.PREFIX) || id.startsWith(MultiblockSpec.LEGACY_PREFIX)) {
            cir.setReturnValue(MultiblockIcon.of(id));
        } else if (id.startsWith(MannequinSpec.PREFIX) || id.startsWith(MannequinSpec.LEGACY_PREFIX)) {
            cir.setReturnValue(MannequinIcon.of(id));
        } else if (id.startsWith(EntitySpec.PREFIX)) {
            cir.setReturnValue(EntityIcon.of(id));
        } else if (id.startsWith(StructureSpec.PREFIX)) {
            cir.setReturnValue(StructureIcon.of(id));
        } else if (id.startsWith(PortalSpec.PREFIX)) {
            cir.setReturnValue(PortalIcon.of(id));
        }
    }
}
