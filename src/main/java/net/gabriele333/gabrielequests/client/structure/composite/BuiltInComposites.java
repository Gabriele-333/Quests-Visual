package net.gabriele333.gabrielequests.client.structure.composite;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The composite recipes shipped with the mod, for structures whose mod builds them in Java
 * and which therefore cannot be assembled from data (no template pools to solve).
 *
 * <p>Each one is keyed by the structure id the mod itself uses, so a display created for
 * {@code twilightforest:lich_tower} keeps working if that mod ever grows real pools - the
 * resolver prefers pools when they exist. A recipe whose templates are missing (mod not
 * installed) simply produces nothing and the entry disappears from the picker.</p>
 *
 * <p>These are silhouettes, not replicas: Twilight Forest's own generator picks the tower's
 * rooms procedurally out of 188 templates, and no fixed list can reproduce a particular
 * instance. The shipped Lich Tower stacks the pieces that define its outline - the entrance
 * foyer, the shaft, the boss room and its roof - which is what reads as "the Lich Tower" in
 * a quest book. Packs that want something different override it by dropping their own JSON
 * in {@code config/questsvisual/composites/} (see {@link CompositeStructures}).</p>
 */
final class BuiltInComposites {

    private BuiltInComposites() {
    }

    static Map<String, CompositeRecipe> all() {
        // Empty by design, and worth saying why rather than deleting the class.
        //
        // The one recipe that used to live here was the Twilight Forest Lich Tower, hand-fitted
        // from the jigsaw blocks its templates carry: 23981 blocks, 35x97x58, and correct as far
        // as it went - but only the foyer, shaft and boss room, because a fixed part list cannot
        // reproduce the wings and bridges TF's generator decides on the fly. The baked instance
        // (see BakedStructures) is that same tower as the generator actually builds it, wings
        // included, at 97x124x135, so keeping the recipe would only mean a worse tower winning
        // over a better one - composites deliberately outrank everything else.
        //
        // The mechanism stays, and stays first in the resolution order, because it is how a pack
        // overrides what this mod ships: drop a JSON in config/questsvisual/composites and it
        // beats the baked instance, the Twilight builders and the jigsaw assembler alike.
        return new LinkedHashMap<>();
    }
}
