package net.gabriele333.gabrielequests.client.mannequin;

import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import net.gabriele333.gabrielequests.client.DisplayOrbitDrag;
import net.gabriele333.gabrielequests.client.OrbitDraggable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * An FTB Library {@link Icon} that shows a rotatable player mannequin (the local player's
 * skin, wearing the armour and holding the weapons chosen in the spec). Serialised as its
 * {@link #toString()} (the {@link MannequinSpec} string) exactly like {@code ItemIcon} and
 * {@code MultiblockIcon}, so it rides {@code ChapterImage}'s save/sync for free; the
 * {@code IconMixin} on {@code Icon.getIcon(String)} rebuilds it from the string.
 *
 * <p><b>Server safety:</b> like the multiblock icon, this is loadable on a dedicated server
 * (chapters are re-serialised there). Its fields reference only common classes; everything
 * client-only ({@link MannequinRenderer}, entity rendering, GL) is reached exclusively
 * inside {@link #draw}. Unparseable strings are kept verbatim so a save/sync never loses
 * data it cannot understand.</p>
 *
 * <p>Mannequins are always {@link OrbitDraggable#orbitViewRotate() view-rotatable}, so any
 * player can drag to inspect them; in the editor the drag persists like any image edit.</p>
 */
public class MannequinIcon extends Icon implements OrbitDraggable {

    private final String raw;
    @Nullable
    private final MannequinSpec spec;

    public MannequinIcon(MannequinSpec spec) {
        this.spec = spec.normalized();
        this.raw = this.spec.serialize();
    }

    private MannequinIcon(String raw) {
        this.spec = null;
        this.raw = raw;
    }

    /** Parses the serialised form; keeps the raw string as-is when it is malformed. */
    public static MannequinIcon of(String serialized) {
        MannequinSpec parsed = MannequinSpec.parse(serialized);
        return parsed != null ? new MannequinIcon(parsed) : new MannequinIcon(serialized);
    }

    @Nullable
    public MannequinSpec spec() {
        return spec;
    }

    public Component displayName() {
        return Component.translatable("gabrielequests.mannequin.name");
    }

    @Override
    public void draw(GuiGraphics graphics, int x, int y, int w, int h) {
        if (spec == null) {
            Color4I.rgb(0x20_20_20).withAlpha(140).draw(graphics, x, y, w, h);
            return;
        }
        int[] view = DisplayOrbitDrag.currentView();
        int yaw = view != null ? view[0] : spec.yaw();
        int pitch = view != null ? view[1] : spec.pitch();
        MannequinRenderer.render(graphics, x, y, w, h, spec, yaw, pitch);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return o == this || (o instanceof MannequinIcon other && other.raw.equals(raw));
    }

    // ---------------------------------------------------------------- OrbitDraggable

    @Override
    public boolean orbitEnabled() {
        return spec != null;
    }

    @Override
    public boolean orbitViewRotate() {
        return true; // mannequins are always rotatable, for editors and players alike
    }

    @Override
    public int orbitYaw() {
        return spec != null ? spec.yaw() : MannequinSpec.DEFAULT_YAW;
    }

    @Override
    public int orbitPitch() {
        return spec != null ? spec.pitch() : MannequinSpec.DEFAULT_PITCH;
    }

    @Override
    @Nullable
    public Icon orbitWithRotation(int yaw, int pitch) {
        return spec != null ? new MannequinIcon(spec.withRotation(yaw, pitch)) : this;
    }
}
