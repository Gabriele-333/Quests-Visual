package net.gabriele333.gabrielequests.client.structure.composite;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written description of a whole structure, for the mods that build their structures
 * in Java instead of with jigsaw pools. Twilight Forest is the motivating case: it ships 274
 * templates and <b>zero</b> template pools, so its Lich Tower only exists as a pile of rooms
 * that its own generator stacks - there is nothing for
 * {@link net.gabriele333.gabrielequests.client.structure.jigsaw.JigsawAssembler} to solve.
 *
 * <p>A recipe is just an ordered list of parts. Each part either sits at an explicit offset
 * or is <em>stacked</em>: dropped on top of everything placed so far and centred on the
 * running footprint, which is what a tower is. {@code repeat} stacks the same template
 * several times, so a shaft of identical slices is one line.</p>
 *
 * <p>These are deliberately approximations of the real generated structure - the mod's
 * generator is not a thing we can run - so a recipe aims at a recognisable silhouette, not
 * at a byte-identical copy. Packs can add their own without touching code: see
 * {@link CompositeStructures} for the JSON form.</p>
 */
public record CompositeRecipe(List<Part> parts) {

    /**
     * One placement. Exactly one of {@code offset} (explicit) or {@code stack} (on top of
     * what is already there, horizontally centred) applies. With {@code repeat} above 1 the
     * template is placed that many times, each copy moved a further {@code step} - which is
     * how a tower shaft of identical floors becomes one line.
     */
    public record Part(String templateId, @Nullable BlockPos offset, boolean stack, int repeat,
                       BlockPos step, Rotation rotation) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Small fluent helper so the built-in recipes read like the tower they describe. */
    public static final class Builder {

        private final List<Part> parts = new ArrayList<>();

        private Builder() {
        }

        /** Places a template once, at an explicit offset from the structure origin. */
        public Builder at(String templateId, int x, int y, int z) {
            return at(templateId, x, y, z, Rotation.NONE);
        }

        /** As {@link #at(String, int, int, int)}, turned. */
        public Builder at(String templateId, int x, int y, int z, Rotation rotation) {
            parts.add(new Part(templateId, new BlockPos(x, y, z), false, 1, BlockPos.ZERO, rotation));
            return this;
        }

        /**
         * Places {@code times} copies starting at an explicit offset, each one moved a
         * further {@code (stepX, stepY, stepZ)} - a repeated floor, a row of pillars.
         */
        public Builder repeat(String templateId, int x, int y, int z, int times,
                              int stepX, int stepY, int stepZ) {
            parts.add(new Part(templateId, new BlockPos(x, y, z), false, Math.max(times, 1),
                    new BlockPos(stepX, stepY, stepZ), Rotation.NONE));
            return this;
        }

        /** Drops a template on top of the stack so far, centred on the running footprint. */
        public Builder stack(String templateId) {
            return stack(templateId, 1);
        }

        /** As {@link #stack(String)}, {@code times} copies on top of each other. */
        public Builder stack(String templateId, int times) {
            parts.add(new Part(templateId, null, true, Math.max(times, 1), BlockPos.ZERO, Rotation.NONE));
            return this;
        }

        public CompositeRecipe build() {
            return new CompositeRecipe(List.copyOf(parts));
        }
    }
}
