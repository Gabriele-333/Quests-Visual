package net.gabriele333.gabrielequests.client.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Mekanism Generators-only hook for the sandboxed BER phase, referenced behind a
 * {@code ModList.isLoaded("mekanismgenerators")} guard in {@link MultiblockRenderer}.
 *
 * <p>Turbine blades are <b>items housed inside the rotor block entities</b>, not blocks, so
 * they cannot be part of the generated structure map - a pristine fake rotor has
 * {@code blades == 0} and {@code RenderTurbineRotor} returns immediately, which is why the
 * industrial turbine used to render as a bare rotor column. This fills in the two fields
 * that renderer reads: {@code blades} (2, a full rotor) and {@code position} (the rotor's
 * index from the bottom of the column, which drives the blade size - radius grows every 4
 * rotors - and the 5&#176;-per-index twist). The blades then render exactly like an
 * unformed, hand-bladed rotor stack does in game.</p>
 *
 * <p>Mekanism is a {@code localRuntime}-only dependency (not published to consumers), so
 * everything here goes through reflection on stable, unobfuscated field names; a failure
 * costs the blades and nothing else (the caller's per-block try/catch takes over, and the
 * resolved-field cache means a missing field is looked up once per session).</p>
 */
final class MekanismBeSetup {

    private static final String ROTOR_ID = "mekanismgenerators:turbine_rotor";

    private static boolean fieldsResolved;
    private static Field bladesField;
    private static Field positionField;

    private MekanismBeSetup() {
    }

    static void configure(BlockEntity be, BlockPos pos, Map<BlockPos, BlockState> blocks) {
        BlockState state = blocks.get(pos);
        if (state == null || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(ROTOR_ID)) {
            return;
        }
        if (!fieldsResolved) {
            fieldsResolved = true;
            resolveFields(be.getClass());
        }
        if (bladesField == null || positionField == null) {
            return;
        }
        try {
            bladesField.setInt(be, 2);
            positionField.setInt(be, rotorIndex(pos, blocks));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LoggerFactory.getLogger("GabrieleQuests/QuestsTools").warn(
                    "[GabrieleQuests/QuestsTools] Could not fill in turbine rotor blades", e);
            bladesField = null;
            positionField = null;
        }
    }

    /** Number of contiguous rotors below this one - Mekanism's own rotor "position". */
    private static int rotorIndex(BlockPos pos, Map<BlockPos, BlockState> blocks) {
        int index = 0;
        for (BlockPos below = pos.below(); ; below = below.below()) {
            BlockState state = blocks.get(below);
            if (state == null || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(ROTOR_ID)) {
                return index;
            }
            index++;
        }
    }

    private static void resolveFields(Class<?> rotorClass) {
        try {
            for (Class<?> c = rotorClass; c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().endsWith("TileEntityTurbineRotor")) {
                    bladesField = c.getDeclaredField("blades");
                    bladesField.setAccessible(true);
                    positionField = c.getDeclaredField("position");
                    positionField.setAccessible(true);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            bladesField = null;
            positionField = null;
            LoggerFactory.getLogger("GabrieleQuests/QuestsTools").warn(
                    "[GabrieleQuests/QuestsTools] Mekanism turbine rotor fields not found; blades will not render", e);
        }
    }
}
