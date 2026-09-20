package net.gabriele333.gabrielequests.client.render;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jetbrains.annotations.Nullable;

/**
 * Registry-name to {@link BlockState} lookups for the scene-building features (display
 * multiblock, display portal). Everything goes through <b>ids only</b>, never a class or a
 * static {@code Blocks} field of a mod, which is what keeps those features free of
 * compile-time and hard runtime dependencies: an id no mod provides simply yields
 * {@code null} and the caller degrades to "this structure cannot be rendered" instead of
 * crashing.
 */
public final class BlockStates {

    private BlockStates() {
    }

    /** The default state of a block by id, or {@code null} when nothing is registered under it. */
    @Nullable
    public static BlockState state(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(rl).defaultBlockState();
    }

    /** True when a block is registered under this id. */
    public static boolean exists(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.BLOCK.containsKey(rl);
    }

    /**
     * Sets a blockstate property <b>by name</b>; a no-op when the state is null, does not
     * have that property or would not accept the value. Same reasoning as above: the
     * property belongs to a block we only know by id, so it can never be referenced
     * statically.
     */
    @Nullable
    public static BlockState with(@Nullable BlockState state, String property, String value) {
        if (state == null) {
            return null;
        }
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals(property)) {
                return withValue(state, prop, value);
            }
        }
        return state;
    }

    /** True when the state has a property of that name (so callers can skip pointless work). */
    public static boolean has(@Nullable BlockState state, String property) {
        if (state == null) {
            return false;
        }
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals(property)) {
                return true;
            }
        }
        return false;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> prop,
                                                                 String value) {
        return prop.getValue(value).map(v -> state.setValue(prop, v)).orElse(state);
    }
}
