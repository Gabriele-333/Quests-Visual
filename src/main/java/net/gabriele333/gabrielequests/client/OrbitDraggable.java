package net.gabriele333.gabrielequests.client;

import dev.ftb.mods.ftblibrary.icon.Icon;
import org.jetbrains.annotations.Nullable;

/**
 * A display {@link Icon} that can be orbit-rotated by holding the left mouse button on it
 * (see {@link DisplayOrbitDrag}). Implemented by the display multiblock, mannequin and
 * entity icons, so the single drag handler serves all three.
 *
 * <p>The angles are whole degrees: {@link #orbitYaw()} spins the model around the vertical
 * axis, {@link #orbitPitch()} tilts it. {@link #orbitWithRotation} produces the icon to
 * store once a drag in edit mode is released (it must carry the new angles and otherwise be
 * equal to this icon).</p>
 */
public interface OrbitDraggable {

    /** False when the icon holds an unparseable string and cannot be rotated at all. */
    boolean orbitEnabled();

    /** True if non-editors may rotate it too (a view-only, non-persisting drag). */
    boolean orbitViewRotate();

    int orbitYaw();

    int orbitPitch();

    /** A copy of this icon carrying the given angles, or {@code null} if it cannot be built. */
    @Nullable
    Icon orbitWithRotation(int yaw, int pitch);
}
