package net.gabriele333.gabrielequests.client;

import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.DoubleConfig;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftblibrary.icon.Icons;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftbquests.quest.Movable;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the "Rotate", "Flip horizontal" and "Flip vertical" context-menu entries that
 * transform the position of every selected object at once (or of a single right-clicked
 * object when nothing is selected).
 *
 * <p>These operate on {@link Movable} - so quests, chapter images/display items and quest
 * links are all moved together - by re-computing each object's position and re-using FTB
 * Quests' own move plumbing: {@link Movable#requestMove} sends a
 * {@code MoveMovableMessage}, the server validates {@code canEdit}, applies the move and
 * broadcasts a {@code MoveMovableResponseMessage} which makes every client run
 * {@code onMoved(...)} and refresh the panel (position, persistence and sync come for
 * free, exactly like a drag-move). Objects the author marked as position-locked are
 * skipped by that call, so they stay put here too.</p>
 *
 * <p><b>Y axis is inverted.</b> FTB Quests lays the map out in screen space: X grows to the
 * right, Y grows <em>downward</em>. A reflection is symmetric so flips are unaffected, but
 * for a rotation the sign of the sine terms decides the visual direction. We use the
 * mathematical convention (a positive angle rotates <em>counter-clockwise</em> as seen on
 * screen), which in a Y-down space means:
 * <pre>
 *     dx' =  dx*cos(a) + dy*sin(a)
 *     dy' = -dx*sin(a) + dy*cos(a)
 * </pre>
 *
 * <p>The rotate dialog re-uses FTB Library's {@link EditConfigScreen} (the same multi-field
 * editor FTB Quests opens for object properties): angle plus a pivot X/Y pre-filled with the
 * centre of the selection's bounding box, so the pivot is offered but editable. Because
 * {@code EditConfigScreen} does not auto-close by default, the group callback re-opens the
 * quest editor on both accept and cancel - mirroring {@code ChapterImageButton}'s own edit
 * flow.</p>
 */
public final class TransformMenu {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Translation key of FTB Quests' "Change Size for all..." multi-selection entry. */
    private static final String BULK_SIZE_KEY = "ftbquests.gui.bulk_change_size";

    /** Keys of our own "Set shape" entries; transform items are placed right after them. */
    private static final List<String> ANCHOR_KEYS = List.of(
            "gabrielequests.set_shape", "gabrielequests.set_shape.multi", BULK_SIZE_KEY);

    /** Kills the tiny floating-point noise cos/sin leave on axis-aligned rotations. */
    private static final double PRECISION = 10000.0;

    private TransformMenu() {
    }

    /**
     * The objects a transform should apply to: every selected {@link Movable}, or - if
     * nothing is selected - the right-clicked object when it is itself movable. Returns a
     * fresh snapshot so the captured targets survive later selection changes.
     */
    public static List<Movable> resolveTargets(List<Movable> selectedObjects, QuestObjectBase clicked) {
        List<Movable> targets = new ArrayList<>(selectedObjects);
        if (targets.isEmpty() && clicked instanceof Movable movable) {
            targets.add(movable);
        }
        return targets;
    }

    /**
     * Adds "Rotate", "Flip horizontal" and "Flip vertical" to a context menu, clustered
     * right after the "Set shape" / "Change Size for all..." entries when present (otherwise
     * appended). No-op if there are no targets.
     */
    public static void insertTransformItems(List<ContextMenuItem> menu, List<Movable> targets) {
        if (targets.isEmpty()) {
            return;
        }

        List<ContextMenuItem> items = new ArrayList<>();
        items.add(new ContextMenuItem(
                Component.translatable("gabrielequests.rotate"),
                Icons.REFRESH,
                button -> openRotateDialog(button.getGui(), targets)));
        items.add(new ContextMenuItem(
                Component.translatable("gabrielequests.flip_horizontal"),
                Icons.RIGHT,
                button -> flip(targets, true)));
        items.add(new ContextMenuItem(
                Component.translatable("gabrielequests.flip_vertical"),
                Icons.DOWN,
                button -> flip(targets, false)));

        int insertAt = anchorIndex(menu);
        if (insertAt >= 0) {
            menu.addAll(insertAt, items);
        } else {
            menu.addAll(items);
        }
    }

    /** Index right after the last "Set shape"/"bulk change size" entry, or -1 if none. */
    private static int anchorIndex(List<ContextMenuItem> menu) {
        int insertAt = -1;
        for (int i = 0; i < menu.size(); i++) {
            if (menu.get(i).getTitle().getContents() instanceof TranslatableContents tc
                    && ANCHOR_KEYS.contains(tc.getKey())) {
                insertAt = i + 1;
            }
        }
        return insertAt;
    }

    /**
     * Opens the rotate dialog: an {@link EditConfigScreen} with angle + pivot X/Y fields.
     * The pivot defaults to the centre of the selection's bounding box; on accept every
     * target is rotated around it. The group callback runs on both accept and cancel and
     * always re-opens the quest editor (the screen does not auto-close by default).
     */
    private static void openRotateDialog(BaseScreen gui, List<Movable> targets) {
        double[] centre = boundingBoxCentre(targets);

        // Populated below; captured by the group callback which fires after the field
        // setters, so reading getValue() at that point yields the edited values.
        final DoubleConfig[] cfg = new DoubleConfig[3];

        ConfigGroup group = new ConfigGroup("gabrielequests", accepted -> {
            if (accepted) {
                applyRotation(targets, cfg[0].getValue(), cfg[1].getValue(), cfg[2].getValue());
            }
            gui.openGui();
        });
        group.setNameKey("gabrielequests.rotate.title");

        cfg[0] = group.addDouble("angle", 90.0, v -> {
        }, 90.0, -3600.0, 3600.0);
        cfg[0].setNameKey("gabrielequests.rotate.angle");
        cfg[1] = group.addDouble("pivot_x", centre[0], v -> {
        }, centre[0], -1.0E7, 1.0E7);
        cfg[1].setNameKey("gabrielequests.rotate.pivot_x");
        cfg[2] = group.addDouble("pivot_y", centre[1], v -> {
        }, centre[1], -1.0E7, 1.0E7);
        cfg[2].setNameKey("gabrielequests.rotate.pivot_y");

        new EditConfigScreen(group).openGui();
    }

    /**
     * Rotates every target around {@code (px, py)} by {@code angleDeg} degrees. Positive is
     * counter-clockwise <em>on screen</em>; the sine terms are signed for FTB Quests'
     * inverted (Y-down) axis - see the class javadoc.
     */
    private static void applyRotation(List<Movable> targets, double angleDeg, double px, double py) {
        double rad = Math.toRadians(angleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        LOGGER.info("[GabrieleQuests/QuestsTools] rotate {} object(s) by {} deg around ({}, {})",
                targets.size(), angleDeg, px, py);
        for (Movable movable : targets) {
            double dx = movable.getX() - px;
            double dy = movable.getY() - py;
            double nx = px + dx * cos + dy * sin;
            double ny = py - dx * sin + dy * cos;
            move(movable, nx, ny);
        }
    }

    /**
     * Mirrors every target across the selection centre. Horizontal flip reflects X (left to
     * right), vertical flip reflects Y (top to bottom); reflections are unaffected by the
     * inverted Y axis.
     */
    private static void flip(List<Movable> targets, boolean horizontal) {
        double[] centre = boundingBoxCentre(targets);
        LOGGER.info("[GabrieleQuests/QuestsTools] flip {} object(s) {}", targets.size(),
                horizontal ? "horizontally" : "vertically");
        for (Movable movable : targets) {
            double nx = horizontal ? 2.0 * centre[0] - movable.getX() : movable.getX();
            double ny = horizontal ? movable.getY() : 2.0 * centre[1] - movable.getY();
            move(movable, nx, ny);
        }
    }

    /** Centre of the axis-aligned bounding box of the targets' position points. */
    private static double[] boundingBoxCentre(List<Movable> targets) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Movable movable : targets) {
            minX = Math.min(minX, movable.getX());
            maxX = Math.max(maxX, movable.getX());
            minY = Math.min(minY, movable.getY());
            maxY = Math.max(maxY, movable.getY());
        }
        return new double[]{(minX + maxX) / 2.0, (minY + maxY) / 2.0};
    }

    /**
     * Moves a single object through FTB Quests' own networked move (persist + sync +
     * refresh). Coordinates are rounded to a fixed precision to remove floating-point noise.
     */
    private static void move(Movable movable, double x, double y) {
        movable.requestMove(movable.getChapter(), round(x), round(y));
    }

    private static double round(double v) {
        return Math.round(v * PRECISION) / PRECISION;
    }
}
