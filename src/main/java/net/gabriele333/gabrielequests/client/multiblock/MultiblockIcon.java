package net.gabriele333.gabrielequests.client.multiblock;

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
 * An FTB Library {@link Icon} that shows a Mekanism multiblock structure. Serialised as
 * its {@link #toString()} (the {@link MultiblockSpec} string), exactly like
 * {@code ItemIcon} - so it rides {@code ChapterImage}'s existing save/sync for free; the
 * {@code IconMixin} on {@code Icon.getIcon(String)} turns the string back into this class.
 *
 * <p><b>Server safety:</b> this class is deliberately loadable on a dedicated server
 * (chapter files are read and re-serialised server-side): its fields reference only
 * common classes, and everything client-only ({@link MultiblockRenderer}, GL) is reached
 * exclusively inside {@link #draw}, which only runs on the client. If the raw string
 * cannot be parsed, the icon keeps it verbatim so a save/sync cycle never destroys data
 * it does not understand.</p>
 *
 * <p>Drawing blits the texture that {@link MultiblockRenderer} produced once for this
 * spec (V flipped: framebuffer textures are bottom-up). If there is no texture - Mekanism
 * missing or the one-time render failed - it draws a flat placeholder instead. Either
 * way it never throws and it leaves the render state the way FTB widgets expect
 * ({@link GuiHelper#setupDrawing()}).</p>
 */
public class MultiblockIcon extends Icon implements OrbitDraggable {

    private final String raw;
    @Nullable
    private final MultiblockSpec spec;

    public MultiblockIcon(MultiblockSpec spec) {
        this.spec = spec.normalized();
        this.raw = this.spec.serialize();
    }

    private MultiblockIcon(String raw) {
        this.spec = null;
        this.raw = raw;
    }

    /** Parses the serialised form; keeps the raw string as-is when it is malformed. */
    public static MultiblockIcon of(String serialized) {
        MultiblockSpec parsed = MultiblockSpec.parse(serialized);
        return parsed != null ? new MultiblockIcon(parsed) : new MultiblockIcon(serialized);
    }

    @Nullable
    public MultiblockSpec spec() {
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
        return spec != null ? spec.yaw() : MultiblockSpec.DEFAULT_YAW;
    }

    @Override
    public int orbitPitch() {
        return spec != null ? spec.pitch() : MultiblockSpec.DEFAULT_PITCH;
    }

    @Override
    @Nullable
    public Icon orbitWithRotation(int yaw, int pitch) {
        return spec != null ? new MultiblockIcon(spec.withRotation(yaw, pitch)) : this;
    }

    /** Short human-readable name, e.g. "Dynamic Tank 5x5x5" - used by the UI fixes. */
    public Component displayName() {
        if (spec == null) {
            return Component.literal(raw);
        }
        return Component.translatable(spec.type().translationKey())
                .append(" " + spec.width() + "×" + spec.height() + "×" + spec.depth());
    }

    @Override
    public void draw(GuiGraphics graphics, int x, int y, int w, int h) {
        RenderTarget target;
        int[] view = DisplayOrbitDrag.currentView();
        if (spec == null) {
            target = null;
        } else if (spec.type().animated()) {
            // Animated types (BER visuals) re-render every frame through the live path.
            int yaw = view != null ? view[0] : spec.yaw();
            int pitch = view != null ? view[1] : spec.pitch();
            target = MultiblockRenderer.preview(spec, yaw, pitch, graphics);
        } else if (view != null && DisplayOrbitDrag.isLiveDrag()) {
            // Actively being dragged: the angle changes constantly, so use the shared scratch
            // target rather than filling the cache with one entry per degree.
            target = MultiblockRenderer.preview(spec, view[0], view[1], graphics);
        } else if (view != null) {
            // A held angle from an earlier drag - stable, so it caches like any other view.
            target = MultiblockRenderer.get(spec, view[0], view[1], graphics);
        } else {
            target = MultiblockRenderer.get(spec, spec.yaw(), spec.pitch(), graphics);
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
        return o == this || (o instanceof MultiblockIcon other && other.raw.equals(raw));
    }
}
