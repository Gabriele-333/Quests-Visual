package net.gabriele333.gabrielequests.client.structure.aether;

import net.gabriele333.gabrielequests.client.structure.TemplateAssembly;

/**
 * A dungeon under construction: {@link TemplateAssembly} - which is where the paste, the piece
 * geometry, the seeded randomness and the budget check live - plus the one thing that is the
 * Aether's own, how a piece name becomes a template id.
 */
final class AetherAssembly extends TemplateAssembly {

    AetherAssembly(String structureId, boolean markers) {
        super(structureId, "Aether", markers);
    }

    /**
     * The Aether files its pieces under the structure's own path
     * ({@code aether:bronze_dungeon/lobby} for {@code aether:bronze_dungeon}), which is what
     * lets the builders name pieces and nothing else.
     */
    @Override
    protected String templateId(String piece) {
        return structureId + "/" + piece;
    }
}
