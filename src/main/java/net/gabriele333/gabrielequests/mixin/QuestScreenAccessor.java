package net.gabriele333.gabrielequests.mixin;

import dev.ftb.mods.ftbquests.quest.Movable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes {@code QuestScreen.selectedObjects} (package-private) so code outside FTB Quests'
 * package - namely {@code QuestButtonMixin} - can read the current multi-selection.
 */
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen")
public interface QuestScreenAccessor {

    @Accessor("selectedObjects")
    List<Movable> queststools$selectedObjects();
}
