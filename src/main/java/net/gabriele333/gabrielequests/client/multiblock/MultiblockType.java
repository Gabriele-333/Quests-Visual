package net.gabriele333.gabrielequests.client.multiblock;

import net.gabriele333.gabrielequests.client.render.BlockStates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The multiblocks supported by the "display multiblock" feature - Mekanism (+ Generators),
 * Applied Energistics 2, Ender IO, Draconic Evolution, Ars Nouveau, PneumaticCraft:
 * Repressurized and Mystical Agriculture.
 *
 * <p>Each constant generates a structure that is <b>valid by construction</b>: the layout
 * follows the rules of the corresponding mod's structure validator (checked against the
 * mod sources), and {@link #clamp} normalises user-provided dimensions into the type's
 * legal range. Validation therefore happens exactly once, when a spec is created or
 * modified - never while rendering.</p>
 *
 * <p>Customisation beyond size/glass goes through {@link Option}s: each type declares a
 * closed list of choices (induction cell/provider tier, fission core layout, crafting
 * storage tier, capacitor bank tier, ...), so whatever the user picks still satisfies the
 * validator. Choices map to blocks/layout inside {@link #generateNormalized} only.</p>
 *
 * <p>Blocks are resolved from the registry <b>by id only</b>, so there is no compile-time
 * or hard runtime dependency on any of these mods: if a block is missing (mod not
 * installed) {@link #generate} returns {@code null}, the type disappears from the menus
 * and existing icons fall back to a placeholder instead of crashing.</p>
 *
 * <p>Validator rules encoded here:</p>
 * <ul>
 *   <li><b>Mekanism cuboids</b> (tank/matrix/boiler/fission): frame edges must be casing;
 *       faces may be casing, valves/ports or structural/reactor glass (glass never on the
 *       frame); min 3&#215;3&#215;3, max 18 per axis.</li>
 *   <li><b>Boiler</b>: one complete horizontal plane of pressure dispersers, superheating
 *       elements below it (stacked up from the interior floor) - needs height &#8805; 5.</li>
 *   <li><b>Thermal Evaporation Plant</b>: fixed 4&#215;4 footprint, height 3..18, solid
 *       base, hollow 2&#215;2 core, top-layer corners omitted (validator ignores them),
 *       exactly one controller on a non-frame wall position.</li>
 *   <li><b>Fission Reactor</b>: interior columns of fuel assemblies with exactly one
 *       control rod assembly directly on top of each column - needs height &#8805; 4;
 *       any column arrangement is valid (checkerboard/full/single).</li>
 *   <li><b>Industrial Turbine</b>: square odd base 5..17, height &#8804; 18 <em>and</em>
 *       &#8804; 4&#215;innerRadius+5 (the "too narrow" rule - blades grow a block of radius
 *       every 4 rotors); rotor column in the exact centre with the rotational complex on
 *       top; a complete pressure disperser plane at the complex level; electromagnetic
 *       coils connected from directly above the complex; vents only at/above the complex
 *       level; saturating condensers above it. (Turbine blades are items inside the
 *       rotors, not blocks: they are not part of the layout, the fake rotor block
 *       entities get them in the BER phase - see {@code MekanismBeSetup}.)</li>
 *   <li><b>Fusion Reactor</b>: fixed 5&#215;5&#215;5 plus-shaped shell (per-face pattern
 *       from the validator's ALLOWED_GRID), controller at the top centre.</li>
 *   <li><b>AE2 Crafting CPU</b>: any solid cuboid of crafting-family blocks, max 16 per
 *       axis, at least one crafting storage.</li>
 *   <li><b>AE2 Quantum Bridge</b>: flat 3&#215;3 ring of quantum rings around a quantum
 *       link chamber.</li>
 *   <li><b>AE2 ME Controller</b>: connected, within a 7&#215;7&#215;7 box, and no block may
 *       have two opposite controller neighbours on more than one axis (else the network
 *       conflicts) - drawn as the bare edge frame of the box, which satisfies that at any
 *       size.</li>
 *   <li><b>Ender IO capacitor banks</b>: any cuboid of same-tier banks connects into one
 *       bank (display capped at 8 per axis).</li>
 *   <li><b>PneumaticCraft Pressure Chamber</b>: hollow <em>cube</em> 3..5 per side, hull
 *       entirely of {@code IBlockPressureChamber} blocks (wall/glass/valve/interface, no
 *       frame rule), at least one valve placed mid-face with its facing axis
 *       perpendicular to that face ({@code PressureChamberValveBlockEntity
 *       .checkIfProperlyFormed}); the assembled look is blockstate-driven ({@code formed}
 *       on valve/glass, positional {@code wall_state} on walls).</li>
 *   <li><b>PneumaticCraft Refinery</b>: refinery controller with 2..4 refinery outputs
 *       stacked in a column directly above it (the controller picks the first output
 *       among its non-down neighbours and walks upward).</li>
 *   <li><b>PneumaticCraft Assembly Line</b>: any group of assembly machines connected by
 *       horizontal adjacency on one level ({@code AssemblyControllerBlockEntity
 *       .findMachines}); drawn as the guidebook cluster - drill/laser flanking the
 *       platform, IO units and controller in a row through it.</li>
 *   <li><b>Mystical Agriculture Infusion Altar</b>: an Infusion Altar with 8 Infusion
 *       Pedestals in a single-layer octagon around it - the four cardinals at distance 3
 *       and the four diagonals at distance 2 ({@code InfusionAltarBlockEntity}'s fixed
 *       pedestal offsets), a 7&#215;7 footprint.</li>
 * </ul>
 */
public enum MultiblockType {

    // ---------------------------------------------------------------- Mekanism

    DYNAMIC_TANK("dynamic_tank", "mekanism", 3, 18, 3, 18, true) {
        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            BlockState casing = state("mekanism:dynamic_tank");
            BlockState glassState = state("mekanism:structural_glass");
            BlockState valve = state("mekanism:dynamic_valve");
            if (casing == null || valve == null || (spec.glass() && glassState == null)) {
                return null;
            }
            Map<BlockPos, BlockState> map = shell(w, h, d, casing, spec.glass() ? glassState : casing);
            map.put(new BlockPos(w / 2, 1, 0), valve);
            map.put(new BlockPos(w / 2, h - 2, d - 1), valve);
            return map;
        }
    },

    INDUCTION_MATRIX("induction_matrix", "mekanism", 3, 18, 3, 18, true) {
        @Override
        public List<Option> options() {
            return List.of(
                    new Option("cell_tier", MEK_TIERS, "basic"),
                    new Option("provider_tier", MEK_TIERS, "basic"));
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            BlockState casing = state("mekanism:induction_casing");
            BlockState glassState = state("mekanism:structural_glass");
            BlockState port = state("mekanism:induction_port");
            BlockState cell = state("mekanism:" + spec.option("cell_tier") + "_induction_cell");
            BlockState provider = state("mekanism:" + spec.option("provider_tier") + "_induction_provider");
            if (casing == null || port == null || cell == null || provider == null
                    || (spec.glass() && glassState == null)) {
                return null;
            }
            Map<BlockPos, BlockState> map = shell(w, h, d, casing, spec.glass() ? glassState : casing);
            // Interior may only contain induction cells/providers; fill it with cells and
            // drop a single provider in the middle (any mix is valid for the validator).
            for (int x = 1; x < w - 1; x++) {
                for (int y = 1; y < h - 1; y++) {
                    for (int z = 1; z < d - 1; z++) {
                        map.put(new BlockPos(x, y, z), cell);
                    }
                }
            }
            map.put(new BlockPos(w / 2, h / 2, d / 2), provider);
            map.put(new BlockPos(w / 2, 1, 0), port);
            map.put(new BlockPos(w / 2, h - 2, d - 1), port);
            return map;
        }
    },

    BOILER("boiler", "mekanism", 3, 18, 5, 18, true) {
        @Override
        public List<Option> options() {
            return List.of(new Option("superheater_layers", List.of("1", "2", "3"), "1"));
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            BlockState casing = state("mekanism:boiler_casing");
            BlockState glassState = state("mekanism:structural_glass");
            BlockState valve = state("mekanism:boiler_valve");
            BlockState disperser = state("mekanism:pressure_disperser");
            BlockState superheater = state("mekanism:superheating_element");
            if (casing == null || valve == null || disperser == null || superheater == null
                    || (spec.glass() && glassState == null)) {
                return null;
            }
            Map<BlockPos, BlockState> map = shell(w, h, d, casing, spec.glass() ? glassState : casing);
            // Superheating elements stacked from the interior floor, a complete disperser
            // plane above them at h-3 (elements just must stay below the plane).
            int layers = Math.min(Integer.parseInt(spec.option("superheater_layers")), h - 4);
            for (int x = 1; x < w - 1; x++) {
                for (int z = 1; z < d - 1; z++) {
                    for (int y = 1; y <= layers; y++) {
                        map.put(new BlockPos(x, y, z), superheater);
                    }
                    map.put(new BlockPos(x, h - 3, z), disperser);
                }
            }
            map.put(new BlockPos(w / 2, 1, 0), valve);      // water in
            map.put(new BlockPos(w / 2, 1, d - 1), valve);  // heated coolant in
            map.put(new BlockPos(w / 2, h - 2, 0), valve);  // steam out
            return map;
        }
    },

    THERMAL_EVAPORATION_PLANT("thermal_evaporation_plant", "mekanism", 4, 4, 3, 18, false) {
        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int h = spec.height();
            BlockState block = state("mekanism:thermal_evaporation_block");
            BlockState valve = state("mekanism:thermal_evaporation_valve");
            BlockState controller = state("mekanism:thermal_evaporation_controller");
            if (block == null || valve == null || controller == null) {
                return null;
            }
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    // Solid 4x4 base.
                    map.put(new BlockPos(x, 0, z), block);
                    boolean ring = x == 0 || x == 3 || z == 0 || z == 3;
                    boolean corner = (x == 0 || x == 3) && (z == 0 || z == 3);
                    // Hollow 2x2 core above the base; the validator ignores the top-layer
                    // corners (that is where Advanced Solar Generators may sit).
                    for (int y = 1; y < h - 1; y++) {
                        if (ring) {
                            map.put(new BlockPos(x, y, z), block);
                        }
                    }
                    if (ring && !corner) {
                        map.put(new BlockPos(x, h - 1, z), block);
                    }
                }
            }
            map.put(new BlockPos(1, 1, 0), controller); // non-corner wall position
            map.put(new BlockPos(0, 1, 2), valve);      // input
            map.put(new BlockPos(3, 1, 1), valve);      // output
            return map;
        }
    },

    FISSION_REACTOR("fission_reactor", "mekanism", 3, 18, 4, 18, true) {
        @Override
        public List<Option> options() {
            return List.of(new Option("core_layout",
                    List.of("checkerboard", "full", "single_column"), "checkerboard"));
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            BlockState casing = state("mekanismgenerators:fission_reactor_casing");
            BlockState glassState = state("mekanismgenerators:reactor_glass");
            BlockState port = state("mekanismgenerators:fission_reactor_port");
            BlockState adapter = state("mekanismgenerators:fission_reactor_logic_adapter");
            BlockState fuel = state("mekanismgenerators:fission_fuel_assembly");
            BlockState rod = state("mekanismgenerators:control_rod_assembly");
            if (casing == null || port == null || adapter == null || fuel == null || rod == null
                    || (spec.glass() && glassState == null)) {
                return null;
            }
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        if (boundaries(x, y, z, w, h, d) >= 2) {
                            map.put(new BlockPos(x, y, z), casing);
                        } else if (y == 0 || y == h - 1) {
                            map.put(new BlockPos(x, y, z), casing);
                        } else if (x == 0 || x == w - 1 || z == 0 || z == d - 1) {
                            map.put(new BlockPos(x, y, z), spec.glass() ? glassState : casing);
                        }
                    }
                }
            }
            // Fuel columns, each capped by exactly one control rod assembly directly
            // above the topmost fuel assembly (validator requirement); the arrangement
            // of columns is free, hence the layout choice.
            String layout = spec.option("core_layout");
            for (int x = 1; x < w - 1; x++) {
                for (int z = 1; z < d - 1; z++) {
                    boolean place = switch (layout) {
                        case "full" -> true;
                        case "single_column" -> x == w / 2 && z == d / 2;
                        default -> ((x + z) & 1) == 0; // checkerboard
                    };
                    if (place) {
                        for (int y = 1; y < h - 2; y++) {
                            map.put(new BlockPos(x, y, z), fuel);
                        }
                        map.put(new BlockPos(x, h - 2, z), rod);
                    }
                }
            }
            map.put(new BlockPos(1, h - 1, 1), port);
            map.put(new BlockPos(w - 2, h - 1, d - 2), port);
            map.put(new BlockPos(w / 2, 1, 0), adapter);
            return map;
        }
    },

    INDUSTRIAL_TURBINE("industrial_turbine", "mekanism", 5, 17, 5, 18, true) {
        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // Square base with odd width (validator requirement); depth follows width.
            int w = Math.clamp(spec.width(), 5, 17);
            if ((w & 1) == 0) {
                w--;
            }
            // "Turbine is too narrow": the validator wants innerRadius >= rotors / 4,
            // because the blades gain a block of radius every 4 rotors and have to fit
            // inside the casing. Its rotor count runs from the bottom casing up to the
            // complex, so with the complex at h-3 it is h-2 - hence h <= 4*innerRadius+5.
            int maxHeight = Math.min(18, 4 * ((w - 3) / 2) + 5);
            return new MultiblockSpec(this, w, Math.clamp(spec.height(), 5, maxHeight), w,
                    spec.glass(), spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            BlockState casing = state("mekanismgenerators:turbine_casing");
            BlockState valve = state("mekanismgenerators:turbine_valve");
            BlockState vent = state("mekanismgenerators:turbine_vent");
            BlockState rotor = state("mekanismgenerators:turbine_rotor");
            BlockState complex = state("mekanismgenerators:rotational_complex");
            BlockState coil = state("mekanismgenerators:electromagnetic_coil");
            BlockState condenser = state("mekanismgenerators:saturating_condenser");
            BlockState disperser = state("mekanism:pressure_disperser");
            BlockState glassState = state("mekanism:structural_glass");
            if (casing == null || valve == null || vent == null || rotor == null
                    || complex == null || coil == null || condenser == null || disperser == null
                    || (spec.glass() && glassState == null)) {
                return null;
            }
            int cx = w / 2;
            int cz = d / 2;
            // Complex as high as possible while leaving one interior layer for the coils.
            int complexY = h - 3;
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        if (boundaries(x, y, z, w, h, d) >= 2) {
                            map.put(new BlockPos(x, y, z), casing);
                        } else if (y == 0) {
                            map.put(new BlockPos(x, y, z), casing);
                        } else if (y == h - 1) {
                            map.put(new BlockPos(x, y, z), vent); // vents allowed at/above the complex
                        } else if (x == 0 || x == w - 1 || z == 0 || z == d - 1) {
                            map.put(new BlockPos(x, y, z), y >= complexY ? vent
                                    : spec.glass() ? glassState : casing);
                        }
                    }
                }
            }
            map.put(new BlockPos(cx, 1, 0), valve);      // steam in
            map.put(new BlockPos(cx, 1, d - 1), valve);  // energy/water out
            // Central rotor column capped by the rotational complex.
            for (int y = 1; y < complexY; y++) {
                map.put(new BlockPos(cx, y, cz), rotor);
            }
            map.put(new BlockPos(cx, complexY, cz), complex);
            // Complete disperser plane around the complex.
            for (int x = 1; x < w - 1; x++) {
                for (int z = 1; z < d - 1; z++) {
                    if (x != cx || z != cz) {
                        map.put(new BlockPos(x, complexY, z), disperser);
                    }
                }
            }
            // Coil cross directly above the complex (connected network), saturating
            // condensers fill the rest of the upper volume.
            map.put(new BlockPos(cx, complexY + 1, cz), coil);
            map.put(new BlockPos(cx - 1, complexY + 1, cz), coil);
            map.put(new BlockPos(cx + 1, complexY + 1, cz), coil);
            map.put(new BlockPos(cx, complexY + 1, cz - 1), coil);
            map.put(new BlockPos(cx, complexY + 1, cz + 1), coil);
            for (int x = 1; x < w - 1; x++) {
                for (int y = complexY + 1; y < h - 1; y++) {
                    for (int z = 1; z < d - 1; z++) {
                        map.putIfAbsent(new BlockPos(x, y, z), condenser);
                    }
                }
            }
            return map;
        }
    },

    FUSION_REACTOR("fusion_reactor", "mekanism", 5, 5, 5, 5, true) {
        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState frame = state("mekanismgenerators:fusion_reactor_frame");
            BlockState port = state("mekanismgenerators:fusion_reactor_port");
            BlockState controller = state("mekanismgenerators:fusion_reactor_controller");
            BlockState adapter = state("mekanismgenerators:fusion_reactor_logic_adapter");
            BlockState laser = state("mekanismgenerators:laser_focus_matrix");
            BlockState glassState = state("mekanismgenerators:reactor_glass");
            if (frame == null || port == null || controller == null || adapter == null
                    || laser == null || (spec.glass() && glassState == null)) {
                return null;
            }
            BlockState wall = spec.glass() ? glassState : frame;
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            // Stamp the validator's per-face pattern on all six faces of the 5x5x5 cube
            // (the grid is symmetric, so shared edges agree between faces). Frames go
            // last so they win over wall cells at overlapping positions.
            for (int pass = 0; pass < 2; pass++) {
                byte want = (byte) (pass == 0 ? 2 : 1);
                BlockState block = pass == 0 ? wall : frame;
                for (int i = 0; i < 5; i++) {
                    for (int j = 0; j < 5; j++) {
                        if (FUSION_GRID[i][j] != want) {
                            continue;
                        }
                        map.put(new BlockPos(i, 0, j), block);
                        map.put(new BlockPos(i, 4, j), block);
                        map.put(new BlockPos(i, j, 0), block);
                        map.put(new BlockPos(i, j, 4), block);
                        map.put(new BlockPos(0, i, j), block);
                        map.put(new BlockPos(4, i, j), block);
                    }
                }
            }
            map.put(new BlockPos(2, 4, 2), controller); // must be top centre
            map.put(new BlockPos(0, 2, 2), port);
            map.put(new BlockPos(4, 2, 2), port);
            map.put(new BlockPos(2, 2, 0), laser);
            map.put(new BlockPos(2, 2, 4), adapter);
            return map;
        }
    },

    // ------------------------------------------------------ Applied Energistics 2

    CRAFTING_CPU("crafting_cpu", "ae2", 1, 16, 1, 16, false) {
        @Override
        public List<Option> options() {
            return List.of(
                    new Option("storage_tier", List.of("1k", "4k", "16k", "64k", "256k"), "4k"),
                    new Option("accelerators", List.of("none", "some", "many"), "some"));
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            // "formed"/"powered" select the active multiblock look; the connected borders
            // additionally need per-position ModelData, provided by Ae2ModelData.
            BlockState unit = formed(state("ae2:crafting_unit"));
            BlockState accel = formed(state("ae2:crafting_accelerator"));
            BlockState storage = formed(state("ae2:" + spec.option("storage_tier") + "_crafting_storage"));
            BlockState monitor = formed(state("ae2:crafting_monitor"));
            if (unit == null || accel == null || storage == null || monitor == null) {
                return null;
            }
            // Any solid cuboid of crafting-family blocks is valid as long as at least one
            // storage is present; even-parity cells are storages, so that always holds.
            String accelerators = spec.option("accelerators");
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        int p = x + y + z;
                        BlockState block;
                        if (p % 2 == 0) {
                            block = storage;
                        } else if (accelerators.equals("many")
                                || (accelerators.equals("some") && p % 4 == 1)) {
                            block = accel;
                        } else {
                            block = unit;
                        }
                        map.put(new BlockPos(x, y, z), block);
                    }
                }
            }
            if (w * h * d >= 4) {
                // A cuboid of >= 4 cells has at least two even-parity cells, so a storage
                // survives even if the monitor lands on one.
                map.put(new BlockPos(w / 2, h / 2, 0), monitor);
            }
            return map;
        }
    },

    QUANTUM_BRIDGE("quantum_bridge", "ae2", 3, 3, 3, 3, false) {
        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            return new MultiblockSpec(this, 3, 3, 1, false, spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState ring = formed(state("ae2:quantum_ring"));
            BlockState link = formed(state("ae2:quantum_link"));
            if (ring == null || link == null) {
                return null;
            }
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < 3; x++) {
                for (int y = 0; y < 3; y++) {
                    map.put(new BlockPos(x, y, 0), x == 1 && y == 1 ? link : ring);
                }
            }
            return map;
        }
    },

    ME_CONTROLLER("controller", "ae2", 1, 7, 1, 7, false) {
        @Override
        public List<Option> options() {
            return List.of(new Option("controller_state", List.of("online", "offline"), "online"));
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState controller = with(state("ae2:controller"), "state", spec.option("controller_state"));
            if (controller == null) {
                return null;
            }
            int w = spec.width();
            int h = spec.height();
            int d = spec.depth();
            // Only the edges of the box (the 12 "wires" of the cube, no faces, no fill):
            // an edge block has its opposite pair on that edge's axis alone and a corner
            // has none, so the whole frame satisfies AE2's "opposite neighbours on at most
            // one axis" rule (ControllerValidator.hasControllerCross) at any size, and it
            // stays a single connected structure. Boxes thin enough that a face collapses
            // onto an edge simply come out filled, which is still legal.
            Set<BlockPos> cells = new LinkedHashSet<>();
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < d; z++) {
                        if (boundaries(x, y, z, w, h, d) >= 2) {
                            cells.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            // The connected look (single block vs column) is a blockstate property the
            // block derives from its neighbours in updateShape(), which our fake level
            // never runs - mirror ControllerBlock.getControllerType() instead.
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (BlockPos pos : cells) {
                map.put(pos, with(controller, "type", controllerRenderType(cells, pos)));
            }
            return map;
        }
    },

    // ------------------------------------------------------------------ Ender IO

    CAPACITOR_BANK("capacitor_bank", "enderio", 1, 8, 1, 8, false) {
        @Override
        public List<Option> options() {
            return List.of(
                    new Option("bank_tier", List.of("basic", "advanced", "vibrant"), "basic"),
                    new Option("charge", List.of("0", "25", "50", "75", "100"), "100"));
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState bank = state("enderio:" + spec.option("bank_tier") + "_capacitor_bank");
            if (bank == null) {
                return null;
            }
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < spec.width(); x++) {
                for (int y = 0; y < spec.height(); y++) {
                    for (int z = 0; z < spec.depth(); z++) {
                        map.put(new BlockPos(x, y, z), bank);
                    }
                }
            }
            return map;
        }
    },

    // ------------------------------------------------------ Draconic Evolution

    DRACONIC_REACTOR("draconic_reactor", "draconicevolution", 9, 15, 8, 11, false) {
        @Override
        public boolean animated() {
            return true; // 6 blocks, all BER: cheap enough to re-render every frame
        }

        @Override
        public List<Option> options() {
            return List.of(new Option("stabilizer_distance", List.of("4", "5", "6", "7"), "4"));
        }

        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // Geometry derives entirely from the stabilizer distance: the validator
            // (TileReactorCore.findComponents) scans 4..7 blocks from the core on each
            // axis and the first non-air block must be a component facing the core.
            int r = stabilizerDistance(spec);
            return new MultiblockSpec(this, 2 * r + 1, r + 4, 2 * r + 1, false,
                    spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState core = state("draconicevolution:reactor_core");
            BlockState stabilizer = state("draconicevolution:reactor_stabilizer");
            BlockState injector = state("draconicevolution:reactor_injector");
            if (core == null || stabilizer == null || injector == null) {
                return null;
            }
            // Classic Y-axis layout: 4 stabilizers on the horizontal axes, energy
            // injector below, all r blocks from the core; the extra height above the
            // core keeps the plasma sphere inside the frame. Every visual comes from
            // block-entity renderers (the baked models are dummies) - the fake block
            // entities are configured by DraconicBeSetup (fuel, temperature, facing).
            int r = stabilizerDistance(spec);
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            map.put(new BlockPos(r, r, r), core);
            map.put(new BlockPos(0, r, r), stabilizer);
            map.put(new BlockPos(2 * r, r, r), stabilizer);
            map.put(new BlockPos(r, r, 0), stabilizer);
            map.put(new BlockPos(r, r, 2 * r), stabilizer);
            map.put(new BlockPos(r, 0, r), injector);
            return map;
        }
    },

    FUSION_CRAFTING("fusion_crafting", "draconicevolution", 5, 9, 1, 9, false) {
        @Override
        public List<Option> options() {
            return List.of(
                    new Option("injector_tier",
                            List.of("basic", "wyvern", "awakened", "chaotic"), "wyvern"),
                    new Option("injector_layout", List.of("plus", "star"), "plus"),
                    new Option("injector_distance", List.of("3", "4", "5"), "3"));
        }

        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // Injectors sit on the axes, facing the core, at a cardinal distance the
            // validator requires to be STRICTLY greater than fusionInjectorMinDist=2
            // (TileFusionCraftingCore.updateInjectors: distance <= minDist is rejected as
            // "injector too close"), so the tightest buildable gap is 3 - a distance-2
            // layout renders fine but cannot actually be assembled in-game. Max range is
            // fusionInjectorRange=16; the display offers 3..5. "plus" is a flat 4-injector
            // cross, "star" adds the vertical pair; the box follows the chosen distance.
            int r = injectorDistance(spec);
            int side = 2 * r + 1;
            boolean star = "star".equals(spec.option("injector_layout"));
            return new MultiblockSpec(this, side, star ? side : 1, side, false,
                    spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState core = state("draconicevolution:crafting_core");
            BlockState injector = state("draconicevolution:"
                    + spec.option("injector_tier") + "_crafting_injector");
            if (core == null || injector == null) {
                return null;
            }
            // Static baked-model render: the core and injector models are complete (no BER
            // needed), so an idle fusion setup looks correct and is cached like any cuboid.
            int r = injectorDistance(spec);
            boolean star = "star".equals(spec.option("injector_layout"));
            int cy = star ? r : 0;
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            map.put(new BlockPos(r, cy, r), core);
            // Each injector's "facing" points at the core (the validator matches
            // getRotation() against that direction), and the baked nozzle follows facing,
            // so they all aim inward like a real build.
            putFacing(map, injector, new BlockPos(0, cy, r), Direction.EAST);
            putFacing(map, injector, new BlockPos(2 * r, cy, r), Direction.WEST);
            putFacing(map, injector, new BlockPos(r, cy, 0), Direction.SOUTH);
            putFacing(map, injector, new BlockPos(r, cy, 2 * r), Direction.NORTH);
            if (star) {
                putFacing(map, injector, new BlockPos(r, 0, r), Direction.UP);
                putFacing(map, injector, new BlockPos(r, 2 * r, r), Direction.DOWN);
            }
            return map;
        }
    },

    // ---------------------------------------------------------------- Ars Nouveau

    ENCHANTING_APPARATUS("enchanting_apparatus", "ars_nouveau", 3, 7, 2, 2, false) {
        @Override
        public List<Option> options() {
            return List.of(new Option("pedestal_radius", List.of("1", "2", "3"), "2"));
        }

        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // The apparatus accepts pedestals anywhere within 3 blocks
            // (EnchantingApparatusTile.pedestalList(pos, 3, level)), so any ring radius
            // 1..3 is valid; the classic build uses radius 2.
            int r = pedestalRadius(spec);
            return new MultiblockSpec(this, 2 * r + 1, 2, 2 * r + 1, false,
                    spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState apparatus = state("ars_nouveau:enchanting_apparatus");
            BlockState core = state("ars_nouveau:arcane_core");
            BlockState pedestal = state("ars_nouveau:arcane_pedestal");
            if (apparatus == null || core == null || pedestal == null) {
                return null;
            }
            // Arcane core on the ground with the apparatus on top (its visual is a
            // GeckoLib BER, handled by the sandboxed BER phase; core and pedestals are
            // plain baked models), 8 pedestals in a ring on the ground around it.
            int r = pedestalRadius(spec);
            int c = r;
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            map.put(new BlockPos(c, 0, c), core);
            map.put(new BlockPos(c, 1, c), apparatus);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        map.put(new BlockPos(c + dx * r, 0, c + dz * r), pedestal);
                    }
                }
            }
            return map;
        }
    },

    ENERGY_CORE("energy_core", "draconicevolution", 3, 15, 3, 15, false) {
        @Override
        public boolean animated() {
            return true; // spinning energy sphere + stabilizer discs, all time-driven BER
        }

        @Override
        public List<Option> options() {
            return List.of(
                    new Option("core_tier",
                            List.of("1", "2", "3", "4", "5", "6", "7", "8"), "6"),
                    new Option("stab_distance",
                            List.of("auto", "3", "4", "5", "6", "8", "10", "12"), "auto"));
        }

        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // An ACTIVE core hides its block shell (replaced by invisible structure
            // blocks in-game) and shows only the floating energy sphere + 4 stabilizers.
            // The box must contain both the sphere (radius grows with tier) and the
            // stabilizer ring (user distance), whichever is larger, so nothing clips.
            int half = coreBoxHalf(spec);
            int side = 2 * half + 1;
            return new MultiblockSpec(this, side, side, side,
                    false, spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            // active=true / large=true = the formed look: baked models go INVISIBLE (the
            // baked pass skips them) and only the block-entity renderers draw - the energy
            // sphere, the large stabilizer discs and (via DraconicBeSetup populating the
            // core's stabilizer positions) the glowing spheres and beams to the core.
            BlockState core = with(state("draconicevolution:energy_core"), "active", "true");
            BlockState stabilizer = with(state("draconicevolution:energy_core_stabilizer"), "large", "true");
            if (core == null || stabilizer == null) {
                return null;
            }
            int sd = coreStabDistance(spec);
            int c = coreBoxHalf(spec);
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            map.put(new BlockPos(c, c, c), core);
            map.put(new BlockPos(c - sd, c, c), stabilizer);
            map.put(new BlockPos(c + sd, c, c), stabilizer);
            map.put(new BlockPos(c, c, c - sd), stabilizer);
            map.put(new BlockPos(c, c, c + sd), stabilizer);
            return map;
        }
    },

    // ------------------------------------------------------------- PneumaticCraft

    PRESSURE_CHAMBER("pressure_chamber", "pneumaticcraft", 3, 5, 3, 5, true) {
        @Override
        public List<Option> options() {
            return List.of(new Option("interfaces", List.of("none", "pair"), "pair"));
        }

        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // The validator only ever checks cubes (checkForShiftedCubeOfSize scans
            // sizes 3..5), so all three dimensions follow the width.
            int side = Math.clamp(spec.width(), 3, 5);
            return new MultiblockSpec(this, side, side, side, spec.glass(),
                    spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            int s = spec.width();
            BlockState wall = state("pneumaticcraft:pressure_chamber_wall");
            BlockState glassState = state("pneumaticcraft:pressure_chamber_glass");
            BlockState valve = state("pneumaticcraft:pressure_chamber_valve");
            BlockState iface = state("pneumaticcraft:pressure_chamber_interface");
            if (wall == null || valve == null || iface == null
                    || (spec.glass() && glassState == null)) {
                return null;
            }
            // Any IBlockPressureChamber block may sit anywhere on the hull (glass on
            // edges included - there is no frame rule); wall-frame + glass-faces just
            // reads best, matching the other shelled types. The assembled look is
            // blockstate-driven: walls carry their hull position in wall_state (the
            // unformed texture is wall_state=none), glass a plain formed flag - with()
            // no-ops on whichever property a block lacks, so both are applied blindly.
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            for (int x = 0; x < s; x++) {
                for (int y = 0; y < s; y++) {
                    for (int z = 0; z < s; z++) {
                        int n = boundaries(x, y, z, s, s, s);
                        if (n == 0) {
                            continue;
                        }
                        BlockState base = n >= 2 || !spec.glass() ? wall : glassState;
                        map.put(new BlockPos(x, y, z), with(with(base,
                                "wall_state", chamberWallState(x, y, z, s)), "formed", "true"));
                    }
                }
            }
            int c = s / 2;
            // The valve must sit mid-face with its FACING axis perpendicular to that
            // face (two mid coordinates + matching axis in checkForShiftedCubeOfSize);
            // formed=true is what the validator sets on the primary valve when the
            // chamber assembles, i.e. the formed look.
            map.put(new BlockPos(c, c, 0), with(with(valve, "facing", "north"), "formed", "true"));
            if ("pair".equals(spec.option("interfaces"))) {
                // Interfaces count as walls (IBlockPressureChamber), so mid-face is
                // always legal; one per side like the classic in/out build.
                map.put(new BlockPos(0, c, c), with(iface, "facing", "west"));
                map.put(new BlockPos(s - 1, c, c), with(iface, "facing", "east"));
            }
            return map;
        }
    },

    REFINERY("refinery", "pneumaticcraft", 1, 1, 3, 5, false) {
        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // 1x1 column: controller + 2..4 outputs (recipes produce 2..4 fluids).
            return new MultiblockSpec(this, 1, Math.clamp(spec.height(), 3, 5), 1,
                    false, spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState controller = state("pneumaticcraft:refinery");
            BlockState output = state("pneumaticcraft:refinery_output");
            if (controller == null || output == null) {
                return null;
            }
            // Controller at the bottom, outputs stacked straight above it: the
            // controller takes the first output among its non-down neighbours and walks
            // upward from there (RefineryControllerBlockEntity), so a plain column is
            // the canonical valid build. Both blocks share the same default facing.
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            map.put(new BlockPos(0, 0, 0), controller);
            for (int y = 1; y < spec.height(); y++) {
                map.put(new BlockPos(0, y, 0), output);
            }
            return map;
        }
    },

    ASSEMBLY_LINE("assembly_line", "pneumaticcraft", 4, 4, 2, 2, false) {
        @Override
        public List<Option> options() {
            return List.of(new Option("machines",
                    List.of("drill", "laser", "drill_laser"), "drill_laser"));
        }

        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // Fixed layout: the machine row plus drill/laser flanking the platform;
            // height 2 keeps framing headroom for the BER arms above the block row.
            boolean both = "drill_laser".equals(spec.option("machines"));
            return new MultiblockSpec(this, 4, 2, both ? 3 : 2, false,
                    spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState controller = state("pneumaticcraft:assembly_controller");
            BlockState importUnit = state("pneumaticcraft:assembly_io_unit_import");
            BlockState exportUnit = state("pneumaticcraft:assembly_io_unit_export");
            BlockState platform = state("pneumaticcraft:assembly_platform");
            BlockState drill = state("pneumaticcraft:assembly_drill");
            BlockState laser = state("pneumaticcraft:assembly_laser");
            if (controller == null || importUnit == null || exportUnit == null
                    || platform == null || drill == null || laser == null) {
                return null;
            }
            // The controller discovers its machines by recursive horizontal adjacency
            // (AssemblyControllerBlockEntity.findMachines - same Y, no diagonals), so
            // any connected single-layer cluster is valid; drill/laser flank the central
            // platform like the guidebook build so their arms reach it. All the moving
            // parts (arms, claws) are BER visuals on the fake block entities, frozen at
            // the rest pose.
            String machines = spec.option("machines");
            boolean hasLaser = !"drill".equals(machines);
            boolean hasDrill = !"laser".equals(machines);
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            if (hasLaser) {
                map.put(new BlockPos(1, 0, 0), laser);
            }
            if (hasDrill) {
                map.put(new BlockPos(1, 0, hasLaser ? 2 : 0), drill);
            }
            map.put(new BlockPos(0, 0, 1), importUnit);
            map.put(new BlockPos(1, 0, 1), platform);
            map.put(new BlockPos(2, 0, 1), exportUnit);
            map.put(new BlockPos(3, 0, 1), controller);
            return map;
        }
    },

    // -------------------------------------------------------- Mystical Agriculture

    INFUSION_ALTAR("infusion_altar", "mysticalagriculture", 7, 7, 1, 1, false) {
        @Override
        public MultiblockSpec clamp(MultiblockSpec spec) {
            // Fixed 7x7 footprint, one layer: the altar checks 8 pedestals at a set of
            // relative positions in a single plane, so dimensions never vary.
            return new MultiblockSpec(this, 7, 1, 7, false,
                    spec.viewRotate(), spec.yaw(), spec.pitch(), spec.options());
        }

        @Override
        @Nullable
        Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec) {
            BlockState altar = state("mysticalagriculture:infusion_altar");
            BlockState pedestal = state("mysticalagriculture:infusion_pedestal");
            if (altar == null || pedestal == null) {
                return null;
            }
            // The Infusion Altar sits in the centre of a 7x7 area with 8 Infusion Pedestals
            // in an octagon around it (InfusionAltarBlockEntity's fixed pedestal offsets):
            // the four cardinal directions at distance 3, the four diagonals at distance 2,
            // all on the same level. Centre is at (3,0,3).
            Map<BlockPos, BlockState> map = new LinkedHashMap<>();
            map.put(new BlockPos(3, 0, 3), altar);
            int[][] pedestals = {
                    {3, 0}, {-3, 0}, {0, 3}, {0, -3},     // cardinals, distance 3
                    {2, 2}, {2, -2}, {-2, 2}, {-2, -2}    // diagonals, distance 2
            };
            for (int[] off : pedestals) {
                map.put(new BlockPos(3 + off[0], 0, 3 + off[1]), pedestal);
            }
            return map;
        }
    };

    /**
     * A closed, validator-safe customisation choice for a multiblock type. UI labels come
     * from {@code gabrielequests.multiblock.<id>} and {@code gabrielequests.multiblock.<id>.<choice>}.
     */
    public record Option(String id, List<String> choices, String defaultChoice) {
    }

    private static final List<String> MEK_TIERS = List.of("basic", "advanced", "elite", "ultimate");

    /** Draconic reactor stabilizer distance, safe against not-yet-normalised specs. */
    private static int stabilizerDistance(MultiblockSpec spec) {
        try {
            return Math.clamp(Integer.parseInt(spec.option("stabilizer_distance")), 4, 7);
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /** Fusion crafting injector distance from the core (3..16 buildable; display 3..5). */
    private static int injectorDistance(MultiblockSpec spec) {
        try {
            return Math.clamp(Integer.parseInt(spec.option("injector_distance")), 3, 5);
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** Places a fusion injector oriented so its nozzle (blockstate facing) points at the core. */
    private static void putFacing(Map<BlockPos, BlockState> map, BlockState injector,
                                  BlockPos pos, Direction facing) {
        map.put(pos, with(injector, "facing", facing.getSerializedName()));
    }

    /**
     * The AE2 controller's {@code type} blockstate value for a position, as
     * {@code ControllerBlock.getControllerType} computes it from the neighbours: a column
     * along the single axis whose two opposite neighbours are both controllers, otherwise
     * the plain block model. (Two such axes is AE2's conflicted case, which the generated
     * layouts never produce, so the {@code inside_a}/{@code inside_b} models are unused.)
     */
    private static String controllerRenderType(Set<BlockPos> cells, BlockPos pos) {
        boolean xx = cells.contains(pos.west()) && cells.contains(pos.east());
        boolean yy = cells.contains(pos.below()) && cells.contains(pos.above());
        boolean zz = cells.contains(pos.north()) && cells.contains(pos.south());
        if (xx && !yy && !zz) {
            return "column_x";
        }
        if (!xx && yy && !zz) {
            return "column_y";
        }
        if (!xx && !yy && zz) {
            return "column_z";
        }
        return "block";
    }

    /**
     * The {@code wall_state} value the pressure chamber gives a hull block at (x,y,z) of
     * an s-sized cube, exactly as {@code PressureChamberWallBlockEntity.calcNewWallState}
     * computes it (the wall texture atlas is positional: 4 corner variants shared by
     * opposite corners, x/y/z edges, face centre).
     */
    private static String chamberWallState(int x, int y, int z, int s) {
        boolean xMin = x == 0, yMin = y == 0, zMin = z == 0;
        boolean xMax = x == s - 1, yMax = y == s - 1, zMax = z == s - 1;
        if (xMin && yMin && zMin || xMax && yMax && zMax) {
            return "xmin_ymin_zmin";
        }
        if (xMin && yMin && zMax || xMax && yMax && zMin) {
            return "xmin_ymin_zmax";
        }
        if (xMin && yMax && zMax || xMax && yMin && zMin) {
            return "xmin_ymax_zmax";
        }
        if (xMin && yMax && zMin || xMax && yMin && zMax) {
            return "xmin_ymax_zmin";
        }
        boolean xB = xMin || xMax, yB = yMin || yMax, zB = zMin || zMax;
        if (yB && xB) {
            return "xedge";
        }
        if (yB && zB) {
            return "zedge";
        }
        if (!yB && xB && zB) {
            return "yedge";
        }
        return "center";
    }

    /** Enchanting apparatus pedestal ring radius, safe against not-yet-normalised specs. */
    private static int pedestalRadius(MultiblockSpec spec) {
        try {
            return Math.clamp(Integer.parseInt(spec.option("pedestal_radius")), 1, 3);
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    /** Rendered energy-sphere radius from tier ({@code SCALES[tier-1]} is ~ the diameter). */
    private static int coreSphereRadius(MultiblockSpec spec) {
        double[] scales = {1.1, 1.7, 2.3, 3.6, 5.5, 7.1, 8.6, 10.2};
        int tier;
        try {
            tier = Math.clamp(Integer.parseInt(spec.option("core_tier")), 1, 8);
        } catch (NumberFormatException e) {
            tier = 6;
        }
        return Math.max(1, (int) Math.ceil(scales[tier - 1] * 0.5));
    }

    /** Stabilizer distance from the core: "auto" = just outside the sphere, else the choice. */
    private static int coreStabDistance(MultiblockSpec spec) {
        String choice = spec.option("stab_distance");
        if (!"auto".equals(choice)) {
            try {
                return Math.clamp(Integer.parseInt(choice), 2, 16);
            } catch (NumberFormatException ignored) {
                // fall through to auto
            }
        }
        return coreSphereRadius(spec) + 1;
    }

    /** Box half-extent that contains both the sphere and the stabilizer ring, plus margin. */
    private static int coreBoxHalf(MultiblockSpec spec) {
        return Math.max(coreSphereRadius(spec), coreStabDistance(spec)) + 1;
    }

    /**
     * Per-face requirement grid of the Mekanism Fusion Reactor validator
     * ({@code FusionReactorValidator.ALLOWED_GRID}): 0 = empty, 1 = frame, 2 = wall
     * (frame/glass/port/controller/adapter/laser). Symmetric, so face orientation is
     * irrelevant.
     */
    private static final byte[][] FUSION_GRID = {
            {0, 0, 1, 0, 0},
            {0, 1, 2, 1, 0},
            {1, 2, 2, 2, 1},
            {0, 1, 2, 1, 0},
            {0, 0, 1, 0, 0}
    };

    private final String id;
    private final String modGroup;
    private final int minFootprint;
    private final int maxFootprint;
    private final int minHeight;
    private final int maxHeight;
    private final boolean supportsGlass;

    MultiblockType(String id, String modGroup, int minFootprint, int maxFootprint, int minHeight,
                   int maxHeight, boolean supportsGlass) {
        this.id = id;
        this.modGroup = modGroup;
        this.minFootprint = minFootprint;
        this.maxFootprint = maxFootprint;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.supportsGlass = supportsGlass;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "gabrielequests.multiblock." + id;
    }

    /** UI grouping by source mod ("mekanism" also covers Mekanism Generators). */
    public String modGroup() {
        return modGroup;
    }

    public String modGroupTranslationKey() {
        return "gabrielequests.multiblock.mod." + modGroup;
    }

    public boolean supportsGlass() {
        return supportsGlass;
    }

    /** The customisation options this type offers (empty by default). */
    public List<Option> options() {
        return List.of();
    }

    /**
     * Animated types are re-rendered every frame instead of served from the texture
     * cache (their visuals move: plasma sphere, spinning stabilizer rings, beams). Only
     * viable for small structures - the per-frame cost is a full tesselation.
     */
    public boolean animated() {
        return false;
    }

    /** Normalises a spec into this type's legal dimension range (validation, done once). */
    public MultiblockSpec clamp(MultiblockSpec spec) {
        return new MultiblockSpec(this,
                Math.clamp(spec.width(), minFootprint, maxFootprint),
                Math.clamp(spec.height(), minHeight, maxHeight),
                Math.clamp(spec.depth(), minFootprint, maxFootprint),
                spec.glass() && supportsGlass,
                spec.viewRotate(), spec.yaw(), spec.pitch(),
                spec.options());
    }

    /**
     * Generates the block layout for a spec (normalised first), or {@code null} if any
     * required block is not registered (mod absent).
     */
    @Nullable
    public Map<BlockPos, BlockState> generate(MultiblockSpec spec) {
        return generateNormalized(spec.normalized());
    }

    /** True if all blocks this type needs (with default options) exist in the registry. */
    public boolean isAvailable() {
        return generate(new MultiblockSpec(this, minFootprint, minHeight, minFootprint,
                false, Map.of())) != null;
    }

    @Nullable
    abstract Map<BlockPos, BlockState> generateNormalized(MultiblockSpec spec);

    @Nullable
    public static MultiblockType byId(String id) {
        for (MultiblockType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }

    /** The types whose blocks are currently registered (never empty-checked by callers alone). */
    public static List<MultiblockType> availableTypes() {
        List<MultiblockType> list = new ArrayList<>();
        for (MultiblockType type : values()) {
            if (type.isAvailable()) {
                list.add(type);
            }
        }
        return list;
    }

    /** The available types of one mod group, declaration order. */
    public static List<MultiblockType> availableTypes(String modGroup) {
        List<MultiblockType> list = new ArrayList<>();
        for (MultiblockType type : values()) {
            if (type.modGroup.equals(modGroup) && type.isAvailable()) {
                list.add(type);
            }
        }
        return list;
    }

    /** Distinct mod groups that have at least one available type, declaration order. */
    public static List<String> availableModGroups() {
        List<String> list = new ArrayList<>();
        for (MultiblockType type : values()) {
            if (!list.contains(type.modGroup) && type.isAvailable()) {
                list.add(type.modGroup);
            }
        }
        return list;
    }

    /** Blocks by registry name only, so a missing mod costs the type, never a crash. */
    @Nullable
    private static BlockState state(String id) {
        return BlockStates.state(id);
    }

    /** The "formed multiblock" look for blocks that have such state properties. */
    @Nullable
    private static BlockState formed(@Nullable BlockState state) {
        return state == null ? null : with(with(state, "formed", "true"), "powered", "true");
    }

    /** Sets a blockstate property by name; no-op if state is null, lacks it, or value is invalid. */
    @Nullable
    private static BlockState with(@Nullable BlockState state, String property, String value) {
        return BlockStates.with(state, property, value);
    }

    private static int boundaries(int x, int y, int z, int w, int h, int d) {
        int n = 0;
        if (x == 0 || x == w - 1) n++;
        if (y == 0 || y == h - 1) n++;
        if (z == 0 || z == d - 1) n++;
        return n;
    }

    /** Hollow cuboid: casing on the frame (edges), face blocks on the walls. */
    private static Map<BlockPos, BlockState> shell(int w, int h, int d, BlockState frame, BlockState face) {
        Map<BlockPos, BlockState> map = new LinkedHashMap<>();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    int n = boundaries(x, y, z, w, h, d);
                    if (n >= 2) {
                        map.put(new BlockPos(x, y, z), frame);
                    } else if (n == 1) {
                        map.put(new BlockPos(x, y, z), face);
                    }
                }
            }
        }
        return map;
    }
}
