package net.gabriele333.gabrielequests.client;

import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the name plate off the entities our displays draw in the quest book.
 *
 * <p>The mannequin is a fake {@link net.minecraft.client.player.RemotePlayer} wearing the
 * viewer's own profile, and vanilla shows a player's name unconditionally:
 * {@code LivingEntityRenderer#shouldShowName} has none of the "only if it has a custom name"
 * guard {@code MobRenderer} adds, so the only things that can suppress it are being the camera
 * entity (which is why the survival inventory's own player model has no tag) or being more
 * than 64 blocks from the camera. The mannequin is neither: it sits at the world origin, so
 * the viewer's own name floated over the display for anyone standing near spawn and vanished
 * further out - a decoration that changed depending on where the reader happened to be
 * standing.</p>
 *
 * <p>Rather than fight that from the entity side (nothing on the entity is consulted for a
 * player), this listens to NeoForge's {@link RenderNameTagEvent}, which
 * {@code EntityRenderer#render} posts before it decides, and answers {@link TriState#FALSE}
 * for whichever entity a display is drawing right now. The window is one
 * {@code dispatcher.render} call, so nothing in the world is affected; outside it the field is
 * {@code null} and the listener is an identity comparison. It covers the display entity too,
 * for the same reason: those are illustrations, and an entity type whose renderer decides to
 * label itself would be labelling a picture.</p>
 */
public final class DisplayNameTags {

    /** The entity being drawn by a display right now, or {@code null} outside a display draw. */
    @Nullable
    private static Entity current;

    private DisplayNameTags() {
    }

    /**
     * Installs the listener. Called once from the mod constructor on the client; registering
     * lazily from the renderers would mean adding a listener to the game bus while it is
     * dispatching the screen render that got us there.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(RenderNameTagEvent.class, DisplayNameTags::onRenderNameTag);
    }

    /** Suppresses this entity's name plate until the matching {@link #end()}. */
    public static void begin(Entity entity) {
        current = entity;
    }

    public static void end() {
        current = null;
    }

    private static void onRenderNameTag(RenderNameTagEvent event) {
        if (current != null && event.getEntity() == current) {
            event.setCanRender(TriState.FALSE);
        }
    }
}
