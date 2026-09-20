package net.gabriele333.gabrielequests.client.structure;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The half of the structure audit that draws: it runs {@link StructureAudit} with a drawer that
 * goes through the real display path, so the report carries the number that actually matters.
 *
 * <p>It waits for the title screen rather than running at client setup, because block models
 * are not baked until the resource reload has finished and there is nothing to draw with before
 * that.</p>
 */
public final class StructureAuditClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Ticks to let the title screen settle before starting; models bake during the reload. */
    private static final int WARMUP_TICKS = 40;

    private static int ticks;
    private static boolean done;

    private StructureAuditClient() {
    }

    /**
     * Runs the audit once, on the first quiet frame after the game has finished loading.
     * Called every client tick while the audit is armed; a no-op afterwards.
     */
    public static void onClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (done || minecraft.screen == null || minecraft.getOverlay() != null) {
            return;
        }
        if (++ticks < WARMUP_TICKS) {
            return;
        }
        done = true;
        try {
            GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            StructureAudit.run(id -> render(id, graphics));
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] The structure audit itself threw", t);
        }
    }

    /** Draws the structure through the real display path, at the default view angles. */
    private static boolean render(String id, GuiGraphics graphics) {
        try {
            StructureSpec spec = new StructureSpec(true, id, StructureSpec.DEFAULT_YAW,
                    StructureSpec.DEFAULT_PITCH, false, false);
            return StructureRenderer.get(spec, spec.yaw(), spec.pitch(), graphics) != null;
        } catch (Throwable t) {
            LOGGER.error("[GabrieleQuests/QuestsTools] AUDIT {} failed to draw", id, t);
            return false;
        }
    }
}
