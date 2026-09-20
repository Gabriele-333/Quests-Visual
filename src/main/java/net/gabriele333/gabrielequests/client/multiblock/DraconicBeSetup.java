package net.gabriele333.gabrielequests.client.multiblock;

import codechicken.lib.render.CCRenderState;
import com.brandon3055.brandonscore.lib.Vec3D;
import com.brandon3055.draconicevolution.blocks.reactor.tileentity.TileReactorComponent;
import com.brandon3055.draconicevolution.blocks.reactor.tileentity.TileReactorCore;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyCore;
import com.brandon3055.draconicevolution.blocks.tileentity.TileEnergyCoreStabilizer;
import com.brandon3055.draconicevolution.client.DEShaders;
import com.brandon3055.draconicevolution.client.handler.ClientEventHandler;
import com.brandon3055.draconicevolution.client.render.tile.RenderTileReactorCore;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Draconic Evolution-only hook, referenced exclusively behind a
 * {@code ModList.isLoaded("draconicevolution")} guard in {@code MultiblockRenderer}
 * (DE + BrandonsCore + CCL are {@code compileOnly} dependencies - this class never loads
 * when the mod is absent, and any failure inside is caught by the sandboxed BER phase).
 *
 * <p>{@link #configure} makes the fake reactor block entities look like a <b>running</b>
 * reactor: the core's plasma-sphere size derives from fuel and temperature (a pristine
 * core renders a 0.5-block sphere), the shield needs charge and a nonzero shader
 * animation state, stabilizers/injector orient their model by the {@code facing} stored
 * on the block entity (their blockstates carry no properties). The animation fields are
 * re-derived from DE's own client tick counter on every call, mimicking what the real
 * tick methods accumulate ({@code coreAnimation += shaderAnimationState},
 * {@code animRotation += animRotationSpeed} with speed 15 while running) - combined with
 * the per-frame re-render of animated types, the display moves like the real thing.</p>
 *
 * <p>{@link #renderExtras} replicates the stabilizer/injector <b>beams</b>: the core's
 * own {@code renderTransparent} draws them only for components it can resolve through
 * {@code level.getBlockEntity(...)} (none, for fake block entities sitting on
 * structure-local coordinates), so this runs the same public
 * {@code renderShaderBeam} calls with the same uniforms, taking the component positions
 * from the generated structure instead.</p>
 */
final class DraconicBeSetup {

    private DraconicBeSetup() {
    }

    static void configure(BlockEntity be, BlockPos pos, Map<BlockPos, BlockState> blocks,
                          MultiblockSpec spec) {
        float ticks = ClientEventHandler.elapsedTicks % 1_000_000;
        if (be instanceof TileReactorCore core) {
            core.reactableFuel.set(6480D);   // ~4.4-block plasma sphere at this temperature
            core.temperature.set(8000D);
            core.maxShieldCharge.set(1000D);
            core.shieldCharge.set(1000D);
            core.shaderAnimationState.set(1D);
            core.reactorState.set(TileReactorCore.ReactorState.RUNNING);
            core.coreAnimation = ticks;      // real tick: += shaderAnimationState (1/tick)
            core.shieldAnimationState = 1F;  // full shield/beam fx power
            core.animExtractState.set(1D);   // stabilizer extraction-beam power
        } else if (be instanceof TileReactorComponent component) {
            component.animRotationSpeed = 15F;                 // running speed (state * 15)
            component.animRotation = (ticks * 15F) % 360F;     // real tick: += speed
            for (Direction dir : Direction.values()) {
                for (int i = 4; i < 8; i++) {
                    BlockState found = blocks.get(pos.relative(dir, i));
                    if (found != null && BuiltInRegistries.BLOCK.getKey(found.getBlock())
                            .toString().equals("draconicevolution:reactor_core")) {
                        component.facing.set(dir);
                        return;
                    }
                }
            }
        } else if (be instanceof TileEnergyCore energyCore) {
            // Active core: renders only the energy sphere (scale from tier) + stabilizers,
            // the block shell being invisible in-game. Sphere spin is time-driven.
            int tier;
            try {
                tier = Math.clamp(Integer.parseInt(spec.option("core_tier")), 1, 8);
            } catch (NumberFormatException e) {
                tier = 6;
            }
            // tier is a CLIENT_CONTROL managed value: its set() on the client sends the
            // change to the server and then REVERTS the local value (ccscsFlag is false),
            // so a plain set() never sticks and would also spam a packet every animated
            // frame. Write the backing field directly instead.
            setTierDirect(energyCore, tier);
            energyCore.active.set(true);
            // The core's own BER (RenderTileEnergyCore.renderStabilizers) draws the
            // glowing stabilizer spheres and the beams to the core, but only when
            // stabilizersValid is set and stabilizerPositions is populated (the same
            // server-only state the reactor needs). Fill it in from the structure: each
            // slot is the core-minus-stabilizer offset, exactly as findComponents stores.
            int slot = 0;
            for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                if (slot >= energyCore.stabilizerPositions.length) {
                    break;
                }
                if (BuiltInRegistries.BLOCK.getKey(entry.getValue().getBlock()).toString()
                        .equals("draconicevolution:energy_core_stabilizer")) {
                    energyCore.stabilizerPositions[slot].set(pos.subtract(entry.getKey()));
                    slot++;
                }
            }
            energyCore.stabilizersValid.set(slot == 4);
        } else if (be instanceof TileEnergyCoreStabilizer stab) {
            // RenderEnergyCoreStabilizer bails on !isValidMultiBlock, so this flag is what
            // actually makes the stabilizer discs appear.
            stab.isValidMultiBlock.set(true);
            stab.isCoreActive.set(true);
            stab.multiBlockAxis.set(Direction.Axis.Y);
            stab.rotation = (ticks * 4F) % 360F;               // spin while core-active
            stab.coreDirection.set(directionTo(pos, blocks, "draconicevolution:energy_core"));
        }
    }

    // Cached reflective handle to ManagedByte's backing field (BrandonsCore is not
    // obfuscated, so the field name is stable). Set once, reused across frames.
    @Nullable
    private static java.lang.reflect.Field tierValueField;
    private static boolean tierFieldResolved = false;

    private static void setTierDirect(TileEnergyCore core, int tier) {
        try {
            if (!tierFieldResolved) {
                tierFieldResolved = true;
                java.lang.reflect.Field f = core.tier.getClass().getDeclaredField("value");
                f.setAccessible(true);
                tierValueField = f;
            }
            if (tierValueField != null) {
                tierValueField.setByte(core.tier, (byte) tier);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LoggerFactory.getLogger("GabrieleQuests/QuestsTools").warn(
                    "[GabrieleQuests/QuestsTools] Could not set energy core tier directly; falling back", e);
        }
        // Fallback: allow the client-side set to stick (still pings the server).
        core.tier.setCCSCS();
        core.tier.set(tier);
    }

    /** Direction from {@code pos} toward the nearest block with {@code targetId}. */
    private static Direction directionTo(BlockPos pos, Map<BlockPos, BlockState> blocks, String targetId) {
        for (Direction dir : Direction.values()) {
            for (int i = 1; i < 16; i++) {
                BlockState found = blocks.get(pos.relative(dir, i));
                if (found != null && BuiltInRegistries.BLOCK.getKey(found.getBlock())
                        .toString().equals(targetId)) {
                    return dir;
                }
            }
        }
        return Direction.DOWN;
    }

    /** Beam pass, mirroring {@code RenderTileReactorCore.renderTransparent}'s loop. */
    static void renderExtras(BlockEntityRenderer<?> renderer, BlockEntity be, BlockPos pos,
                             Map<BlockPos, BlockState> blocks, PoseStack poseStack,
                             MultiBufferSource buffers) {
        if (!(renderer instanceof RenderTileReactorCore coreRenderer)
                || !(be instanceof TileReactorCore core)) {
            return;
        }
        CCRenderState ccrs = CCRenderState.instance();
        ccrs.reset();
        float coreSize = (float) core.getCoreDiameter() / 2.3F;
        float fxState = core.shieldAnimationState;
        for (Direction direction : Direction.values()) {
            BlockPos componentPos = null;
            boolean injector = false;
            for (int i = 4; i < 8 && componentPos == null; i++) {
                BlockState state = blocks.get(pos.relative(direction, i));
                if (state == null) {
                    continue;
                }
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                if (id.equals("draconicevolution:reactor_stabilizer")) {
                    componentPos = pos.relative(direction, i);
                } else if (id.equals("draconicevolution:reactor_injector")) {
                    componentPos = pos.relative(direction, i);
                    injector = true;
                }
            }
            if (componentPos == null) {
                continue;
            }
            Direction facing = direction.getOpposite();
            float dist = (float) Math.sqrt(componentPos.distSqr(pos));
            Vec3D pos1 = Vec3D.getCenter(componentPos).subtract(new Vec3D(pos)).offset(facing, -0.35D);

            if (injector) {
                Vec3D pos2 = pos1.copy().offset(facing, 0.6D);
                DEShaders.reactorBeamType.glUniformI(2);
                DEShaders.reactorBeamFade.glUniform1f(1F);
                DEShaders.reactorBeamPower.glUniform1f(fxState);
                DEShaders.reactorBeamStartup.glUniform1f(fxState);
                coreRenderer.renderShaderBeam(ccrs, facing, fxState, buffers, poseStack, pos1, 0.1F, 0.1F, 0.6F, true, false);
                DEShaders.reactorBeamFade.glUniform1f(0F);
                coreRenderer.renderShaderBeam(ccrs, facing, fxState, buffers, poseStack, pos2, 0.1F, coreSize / 1.5, dist - (coreSize * 1.3F), false, false);
            } else {
                Vec3D pos2 = pos1.copy().offset(facing, 0.8D);
                // Inner extraction beam
                DEShaders.reactorBeamType.glUniformI(1);
                DEShaders.reactorBeamFade.glUniform1f(1F);
                DEShaders.reactorBeamPower.glUniform1f((float) core.animExtractState.get());
                DEShaders.reactorBeamStartup.glUniform1f((float) core.animExtractState.get());
                coreRenderer.renderShaderBeam(ccrs, facing, fxState, buffers, poseStack, pos1, 0.263F, 0.263F, 0.8F, true, false);
                DEShaders.reactorBeamFade.glUniform1f(0F);
                coreRenderer.renderShaderBeam(ccrs, facing, fxState, buffers, poseStack, pos2, 0.263F, coreSize / 2, dist - (coreSize * 1.3F), false, false);
                // Outer containment beam
                DEShaders.reactorBeamType.glUniformI(0);
                DEShaders.reactorBeamFade.glUniform1f(1F);
                DEShaders.reactorBeamPower.glUniform1f(fxState);
                DEShaders.reactorBeamStartup.glUniform1f(fxState);
                coreRenderer.renderShaderBeam(ccrs, facing, fxState, buffers, poseStack, pos1, 0.355F, 0.355F, 0.8F, true, false);
                DEShaders.reactorBeamFade.glUniform1f(0F);
                coreRenderer.renderShaderBeam(ccrs, facing, fxState, buffers, poseStack, pos2, 0.355F, coreSize, dist - coreSize, false, true);
            }
            ccrs.reset();
        }
    }
}
