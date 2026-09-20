package net.gabriele333.gabrielequests.mixin;

import dev.ftb.mods.ftbquests.quest.Quest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Quest#getShape()} exists but the {@code shape} field is private with no setter.
 * This accessor lets us write it, exactly like the built-in quest config editor
 * does (its callback is literally {@code v -> shape = v}). After writing we still send an
 * {@code EditObjectMessage} so the change is persisted and synced by the server.
 */
@Mixin(Quest.class)
public interface QuestAccessor {

    @Accessor("shape")
    void queststools$setShape(String shape);
}
