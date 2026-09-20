package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftbquests.net.EditObjectMessage;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.WeakHashMap;

/**
 * Render-thread state for the "hold left button to orbit a 3D display" gesture, shared by
 * every {@link OrbitDraggable} icon (display multiblock, mannequin, entity). Same
 * static-flag pattern as {@code BoxSelectState}.
 *
 * <p><b>Two modes.</b> In edit mode the drag <i>persists</i>: on release the new
 * orientation is written into the icon and the image is re-sent with
 * {@code EditObjectMessage}, so it syncs to everyone like any image edit. When the icon
 * allows view rotation ({@link OrbitDraggable#orbitViewRotate()} - always true for
 * mannequins and entities, opt-in for multiblocks), non-editors can drag too, but that is a
 * <i>view</i> drag: it does not persist - the angle is only held client-side (in
 * {@link #heldView}, keyed weakly by the image so it resets when the chapter reloads), and a
 * right-click on the display drops it again ({@link #resetView}).</p>
 *
 * <p><b>Render routing.</b> {@code ChapterImageButtonMixin} sets the {@link #renderContext}
 * (the image about to draw) right before its icon draws, and each icon's {@code draw} asks
 * {@link #currentView()} what angles to use: the live drag angles while this image is being
 * dragged, else the held view angles, else {@code null} (the icon then uses its own saved
 * angles). {@link #begin} starts from the held angle if present, so a view drag continues
 * from where the last one left off.</p>
 */
public final class DisplayOrbitDrag {

    private static final Logger LOGGER = LoggerFactory.getLogger("GabrieleQuests/QuestsTools");

    /** Degrees of rotation per gui-scaled pixel of mouse travel. */
    private static final int DEGREES_PER_PIXEL = 2;

    /** Client-only view angles held after a non-persisting drag, per image. */
    private static final WeakHashMap<ChapterImage, int[]> heldView = new WeakHashMap<>();

    @Nullable
    private static ChapterImage image;
    @Nullable
    private static OrbitDraggable draggable;
    private static boolean persist;
    private static int anchorX;
    private static int anchorY;
    private static int startYaw;
    private static int startPitch;
    private static int yaw;
    private static int pitch;

    /** The image currently being drawn (set by the mixin around each icon draw). */
    @Nullable
    private static ChapterImage renderContext;

    private DisplayOrbitDrag() {
    }

    /**
     * @param persistToServer true in edit mode (persist + sync); false for a view-only
     *                        drag that is merely held client-side.
     */
    public static void begin(ChapterImage draggedImage, OrbitDraggable draggedIcon,
                             int mouseX, int mouseY, boolean persistToServer) {
        if (!draggedIcon.orbitEnabled()) {
            return;
        }
        image = draggedImage;
        draggable = draggedIcon;
        persist = persistToServer;
        anchorX = mouseX;
        anchorY = mouseY;
        int[] held = heldView.get(draggedImage);
        startYaw = held != null ? held[0] : draggedIcon.orbitYaw();
        startPitch = held != null ? held[1] : draggedIcon.orbitPitch();
        yaw = startYaw;
        pitch = startPitch;
    }

    public static boolean isDraggingImage(ChapterImage candidate) {
        return image == candidate;
    }

    /**
     * Recomputes the live angles from the mouse position. Drag right = spin the model,
     * drag up = tilt to look at the top (orbit convention); pitch is clamped so the view
     * never flips over the pole.
     */
    public static void update(int mouseX, int mouseY) {
        yaw = startYaw + (mouseX - anchorX) * DEGREES_PER_PIXEL;
        pitch = Math.clamp(startPitch - (mouseY - anchorY) * DEGREES_PER_PIXEL, -89, 89);
    }

    /** The image the mixin is about to draw; scopes {@link #currentView()}. */
    public static void setRenderContext(@Nullable ChapterImage current) {
        renderContext = current;
    }

    public static void clearRenderContext() {
        renderContext = null;
    }

    /**
     * True when the image about to draw is the one being actively dragged right now, as
     * opposed to merely holding an angle from an earlier drag. Only a live drag needs the
     * renderer's scratch target: a held angle is stable, so it belongs in the normal cache
     * (see {@code SceneCache}), and routing it there is what stops two spun displays on one
     * page from re-rendering each other every frame.
     */
    public static boolean isLiveDrag() {
        return renderContext != null && image == renderContext;
    }

    /**
     * The angles the {@link #renderContext} image should render at: live drag angles while
     * it is being dragged, the held view angles if it has any, otherwise {@code null} (use
     * the icon's own saved angles).
     */
    @Nullable
    public static int[] currentView() {
        if (renderContext == null) {
            return null;
        }
        if (image == renderContext) {
            return new int[]{yaw, pitch};
        }
        return heldView.get(renderContext);
    }

    /**
     * Drops the client-side angle held for an image, so it snaps back to the orientation the
     * quest author saved in the icon. This is what right-clicking a display does
     * ({@code ChapterImageButtonMixin}): a view drag is deliberately not persisted, so "put it
     * back the way the author left it" has to be an explicit gesture.
     *
     * <p>Deliberately <b>not</b> gated on {@code canEdit()}: an editor's drag persists into the
     * icon and never lands here, so for them this is a no-op that leaves the right-click menu
     * alone - while gating on it would silently do nothing in any situation where FTB reports
     * "can edit" and the drag was a view drag anyway.</p>
     *
     * @return true if the image actually had a held angle
     */
    public static boolean resetView(ChapterImage target) {
        if (image == target) {
            // A drag in progress on this image would re-hold its angle on release.
            clear();
        }
        int[] dropped = heldView.remove(target);
        // TEMPORARY diagnostic: this feature depends on FTB routing the right press to the
        // image widget at all, which several guards in ChapterImageButton#checkMouseOver can
        // prevent. One line per right-click on a display tells us whether we get here.
        LOGGER.info("[GabrieleQuests/QuestsTools] Right-click on display (canEdit={}, held={}): {}",
                target.getChapter().getQuestFile().canEdit(), heldView.size(),
                dropped == null ? "no held view angle to reset" : "reset from " + dropped[0] + "x" + dropped[1]);
        return dropped != null;
    }

    /**
     * TEMPORARY diagnostic, called for every right press the quest panel sees (before it is
     * dispatched to the widgets). Pairs with the line in {@link #resetView}: this one alone
     * means the press never reached the display's widget.
     */
    public static void logRightPress() {
        LOGGER.info("[GabrieleQuests/QuestsTools] Right press on the quest panel");
    }

    /** Persists the final orientation (edit mode) or holds it client-side (view mode). */
    public static void commit() {
        if (image != null && draggable != null) {
            int ny = Math.floorMod(yaw, 360);
            if (persist) {
                if (draggable.orbitYaw() != ny || draggable.orbitPitch() != pitch) {
                    Icon updated = draggable.orbitWithRotation(yaw, pitch);
                    if (updated != null) {
                        image.setImage(updated);
                        NetworkManager.sendToServer(EditObjectMessage.forQuestObject(image));
                    }
                }
                heldView.remove(image); // the saved angle now wins
            } else {
                heldView.put(image, new int[]{ny, pitch});
            }
        }
        clear();
    }

    public static void clear() {
        draggable = null;
        image = null;
    }
}
