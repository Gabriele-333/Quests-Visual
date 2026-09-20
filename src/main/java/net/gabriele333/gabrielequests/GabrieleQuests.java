package net.gabriele333.gabrielequests;

import com.mojang.logging.LogUtils;
import net.gabriele333.gabrielequests.client.DisplayNameTags;
import net.gabriele333.gabrielequests.client.structure.StructureAudit;
import net.gabriele333.gabrielequests.client.structure.StructureAuditClient;
import net.gabriele333.gabrielequests.dev.StructureBaker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * GabrieleQuests - reusable editor tools for FTB Quests (box selection, "Set shape" on a
 * multi-selection, display items, display multiblocks and display mannequins).
 *
 * <p>All of the behaviour lives in client-side mixins (see the
 * {@code net.gabriele333.gabrielequests.mixin} package and {@code gabrielequests.mixins.json})
 * plus their client helpers in {@code net.gabriele333.gabrielequests.client}. This class only
 * exists so FML registers the mod and honours the mixin config and mod dependencies declared
 * in {@code neoforge.mods.toml}. There is nothing to register - no blocks, items or events.</p>
 *
 * <p>It was extracted from the FMTT2 mod so the same tools can be reused across modpacks;
 * FMTT2 (and any other pack) now depends on this mod instead of bundling the code.</p>
 */
@Mod(GabrieleQuests.MODID)
public final class GabrieleQuests {

    /** The mod id. Must match the id used in {@code neoforge.mods.toml}. */
    public static final String MODID = "questsvisual";

    private static final Logger LOGGER = LogUtils.getLogger();

    public GabrieleQuests(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("GabrieleQuests loaded - FTB Quests editor tools active");
        // One permanent client listener: it keeps the name plate off the entities the
        // mannequin and entity displays draw, which is the only way to stop a player model
        // from labelling itself. See DisplayNameTags for why it cannot be done entity-side.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            DisplayNameTags.register();
        }
        // The one thing this class registers on request only: the display-structure audit,
        // which builds the whole catalogue once so a pack author can see which structures
        // actually render. See StructureAudit for how to turn it on.
        if (StructureAudit.requested()) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                // On the game bus, not at client setup: the audit draws each structure, and
                // block models are not baked until the resource reload has finished.
                NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,
                        event -> StructureAuditClient.onClientTick());
            } else {
                // A server can only build, never draw - but that is exactly what is wanted for
                // a mod the dev client cannot boot with at all (the Aether: see StructureAudit).
                NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                        event -> StructureAudit.run(null));
            }
        }
        // Likewise the structure baker, which needs a running server (the structure registry
        // is server-side data) and is a development tool, never something a player triggers.
        if (!StructureBaker.requested().isEmpty()) {
            NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
                    event -> StructureBaker.run(event.getServer()));
        }
    }
}
