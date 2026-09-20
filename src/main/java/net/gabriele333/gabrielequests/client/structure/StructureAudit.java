package net.gabriele333.gabrielequests.client.structure;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds every whole structure the current mod set offers and logs what came out.
 *
 * <p>The display structure has one failure mode that looks like every other: a structure that
 * cannot be built draws a placeholder, which is what a missing mod, a broken assembly and an
 * over-budget result all look like from the quest book. Working out which of the hundreds of
 * entries in the picker actually render meant opening them one at a time. This walks the
 * whole catalogue in one pass and prints, per structure, its block count and bounding box or
 * the fact that it produced nothing.</p>
 *
 * <p>It should <b>render</b> each one too, not just build it, and {@link StructureAuditClient}
 * is the half that does. That distinction was learned the hard way: an audit that only built
 * reported thirty-two healthy structures while the lich tower took 9.3 seconds to draw and the
 * aurora palace hung the client outright. Building is cheap and uniform; drawing is where a
 * structure's material mix decides whether it costs 164 ms or nine seconds, so a number that
 * does not include it is not a health check.</p>
 *
 * <p><b>Why the pass itself touches nothing client-side.</b> Because for some mods the build
 * half is all we can ever get. The Aether is the standing example: its jar-in-jar Accessories
 * carries {@code client.model.EntityRenderersMixin}, which targets a lambda by ordinal
 * ({@code lambda$createEntityRenderers$26}, {@code require = 1}) that only exists in the
 * production Minecraft jar - the dev runtime recompiles Minecraft and numbers its lambdas
 * differently, so Mixin fails the injection and FML kills the client at
 * {@code RegisterRenderers}. That mixin is listed under {@code client} in the Accessories
 * config, so a <b>server</b> never applies it and the Aether loads there perfectly well; and a
 * structure is built out of templates and the block registry, with nothing client-side about
 * it. So {@code runServer -PwithAether} confirms every Aether builder for real, and this class
 * must stay loadable where {@code net.minecraft.client} does not exist.</p>
 *
 * <p>Off unless asked for, because it is seconds to minutes of work at start-up depending on
 * the pack: set the {@code gabrielequests.structureAudit} system property or the
 * {@code GABRIELEQUESTS_STRUCTURE_AUDIT} environment variable.</p>
 */
public final class StructureAudit {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    private static final String PROPERTY = "gabrielequests.structureAudit";
    private static final String ENVIRONMENT = "GABRIELEQUESTS_STRUCTURE_AUDIT";

    /** Draws one built structure through the real display path; {@code false} when it failed. */
    @FunctionalInterface
    public interface Drawer {
        boolean draw(String id);
    }

    private StructureAudit() {
    }

    /** Whether the audit has been asked for. */
    public static boolean requested() {
        return Boolean.getBoolean(PROPERTY) || Boolean.parseBoolean(System.getenv(ENVIRONMENT));
    }

    /**
     * One pass over the catalogue.
     *
     * @param drawer draws each structure as well, or {@code null} to only build them - which is
     *               all a dedicated server can do, and all that is available for a mod whose
     *               presence the dev client cannot survive (see the class note)
     */
    public static void run(@Nullable Drawer drawer) {
        long started = System.currentTimeMillis();
        int built = 0;
        int failed = 0;
        LOGGER.info("[GabrieleQuests/QuestsTools] ---- structure audit ({}) ----",
                drawer == null ? "build only" : "build + draw");
        for (String id : WholeStructures.ids()) {
            long each = System.currentTimeMillis();
            StructureData data;
            try {
                data = WholeStructures.build(id, false);
            } catch (Throwable t) {
                LOGGER.error("[GabrieleQuests/QuestsTools] AUDIT {} threw", id, t);
                failed++;
                continue;
            }
            if (data == null) {
                LOGGER.warn("[GabrieleQuests/QuestsTools] AUDIT {} -> NOTHING", id);
                failed++;
                continue;
            }
            long build = System.currentTimeMillis() - each;
            String drawn = "";
            if (drawer != null) {
                long before = System.currentTimeMillis();
                boolean rendered = drawer.draw(id);
                drawn = ", draw " + (System.currentTimeMillis() - before) + " ms"
                        + (rendered ? "" : " (DRAW FAILED)");
            }
            LOGGER.info("[GabrieleQuests/QuestsTools] AUDIT {} -> {} blocks, {}x{}x{}, build {} ms{}",
                    id, data.blocks().size(), data.size().getX(), data.size().getY(),
                    data.size().getZ(), build, drawn);
            built++;
        }
        LOGGER.info("[GabrieleQuests/QuestsTools] ---- structure audit: {} built, {} empty, {} ms ----",
                built, failed, System.currentTimeMillis() - started);
    }
}
