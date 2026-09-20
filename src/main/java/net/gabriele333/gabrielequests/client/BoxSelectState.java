package net.gabriele333.gabrielequests.client;

/**
 * Tiny latch shared between our FTB Quests mixins.
 *
 * <p>FTB Quests already knows how to draw a selection rectangle and to pick every
 * quest inside it - but only for the <em>middle</em> mouse button. This feature lets
 * you trigger that same behaviour with <b>Shift + left-drag</b>.</p>
 *
 * <p>Whether the current drag is a shift box-selection is decided once, when the
 * button is first pressed on empty editor space (in {@code QuestPanelMixin}), and
 * remembered here so the rest of the drag (box rendering in
 * {@code QuestScreenMixin#drawBackground} and the final selection in
 * {@code QuestPanelMixin#mouseReleased}) stays consistent even if the player lets
 * go of Shift mid-drag.</p>
 *
 * <p>Everything here runs on the render thread only, so a plain static flag is
 * enough - no synchronisation required.</p>
 */
public final class BoxSelectState {

    /** {@code true} while a Shift + left-drag box selection is in progress. */
    public static boolean active = false;

    private BoxSelectState() {
    }
}
