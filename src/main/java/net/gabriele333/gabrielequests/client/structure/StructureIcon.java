package net.gabriele333.gabrielequests.client.structure;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ftb.mods.ftblibrary.icon.Color4I;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.ui.GuiHelper;
import net.gabriele333.gabrielequests.client.DisplayOrbitDrag;
import net.gabriele333.gabrielequests.client.OrbitDraggable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * An FTB Library {@link Icon} that shows a world-structure template in 3D. Serialised as
 * its {@link #toString()} (the {@link StructureSpec} string), exactly like
 * {@code MultiblockIcon} - so it rides {@code ChapterImage}'s existing save/sync for free;
 * the {@code IconMixin} on {@code Icon.getIcon(String)} turns the string back into this
 * class.
 *
 * <p><b>Server safety:</b> this class is deliberately loadable on a dedicated server
 * (chapter files are read and re-serialised server-side): its fields reference only common
 * classes, and everything client-only ({@link StructureRenderer}, GL) is reached
 * exclusively inside {@link #draw}, which only runs on the client. If the raw string cannot
 * be parsed, the icon keeps it verbatim so a save/sync cycle never destroys data it does
 * not understand.</p>
 *
 * <p>Drawing blits the texture that {@link StructureRenderer} produced once for this spec
 * (V flipped: framebuffer textures are bottom-up). If there is no texture - the structure's
 * mod is not installed, or the template is unreadable - it draws a flat placeholder
 * instead. Either way it never throws and it leaves the render state the way FTB widgets
 * expect ({@link GuiHelper#setupDrawing()}).</p>
 */
public class StructureIcon extends Icon implements OrbitDraggable {

    private final String raw;
    @Nullable
    private final StructureSpec spec;

    public StructureIcon(StructureSpec spec) {
        this.spec = spec.normalized();
        this.raw = this.spec.serialize();
    }

    private StructureIcon(String raw) {
        this.spec = null;
        this.raw = raw;
    }

    /** Parses the serialised form; keeps the raw string as-is when it is malformed. */
    public static StructureIcon of(String serialized) {
        StructureSpec parsed = StructureSpec.parse(serialized);
        return parsed != null ? new StructureIcon(parsed) : new StructureIcon(serialized);
    }

    @Nullable
    public StructureSpec spec() {
        return spec;
    }

    @Override
    public boolean orbitEnabled() {
        return spec != null;
    }

    @Override
    public boolean orbitViewRotate() {
        return spec != null && spec.viewRotate();
    }

    @Override
    public int orbitYaw() {
        return spec != null ? spec.yaw() : StructureSpec.DEFAULT_YAW;
    }

    @Override
    public int orbitPitch() {
        return spec != null ? spec.pitch() : StructureSpec.DEFAULT_PITCH;
    }

    @Override
    @Nullable
    public Icon orbitWithRotation(int yaw, int pitch) {
        return spec != null ? new StructureIcon(spec.withRotation(yaw, pitch)) : this;
    }

    /** Short human-readable name, e.g. "The Twilight Forest / Lich tower / Tower foyer". */
    public Component displayName() {
        return spec == null ? Component.literal(raw) : StructureIndex.label(spec.structureId());
    }

    @Override
    public void draw(GuiGraphics graphics, int x, int y, int w, int h) {
        RenderTarget target;
        int[] view = DisplayOrbitDrag.currentView();
        if (spec == null) {
            target = null;
        } else if (view != null && DisplayOrbitDrag.isLiveDrag()) {
            // Actively being dragged: the angle changes constantly, so use the shared scratch
            // target rather than filling the cache with one entry per degree.
            target = StructureRenderer.preview(spec, view[0], view[1], graphics);
        } else if (view != null) {
            // A held angle from an earlier drag - stable, so it caches like any other view.
            target = StructureRenderer.get(spec, view[0], view[1], graphics);
        } else {
            target = StructureRenderer.get(spec, spec.yaw(), spec.pitch(), graphics);
        }
        if (target == null) {
            // Placeholder: unobtrusive dark box, still visible/selectable in the editor.
            Color4I.rgb(0x20_20_20).withAlpha(140).draw(graphics, x, y, w, h);
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        Matrix4f matrix = graphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x, y, 0F).setUv(0F, 1F);
        buffer.addVertex(matrix, x, y + h, 0F).setUv(0F, 0F);
        buffer.addVertex(matrix, x + w, y + h, 0F).setUv(1F, 0F);
        buffer.addVertex(matrix, x + w, y, 0F).setUv(1F, 1F);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        GuiHelper.setupDrawing();
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
        return o == this || (o instanceof StructureIcon other && other.raw.equals(raw));
    }
}
