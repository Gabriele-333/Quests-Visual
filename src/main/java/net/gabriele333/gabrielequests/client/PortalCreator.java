package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.NameMap;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.portal.PortalCatalog;
import net.gabriele333.gabrielequests.client.portal.PortalIcon;
import net.gabriele333.gabrielequests.client.portal.PortalShape;
import net.gabriele333.gabrielequests.client.portal.PortalSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Display portal" = a decorative {@link ChapterImage} whose icon is a {@link PortalIcon},
 * mirroring {@link MultiblockCreator} and {@link StructureCreator} one-to-one: same creation
 * flow (build client-side, hand it to the server with {@code CreateObjectMessage}), same
 * persistence trick (the icon serialises to a short string via {@code toString()} and is
 * rebuilt by our {@code IconMixin} on {@code Icon.getIcon}).
 *
 * <p>The portal list ({@link PortalCatalog}) is short enough - a handful per install - that it
 * needs no mod-first context menu like the structure picker: the right-click entry opens the
 * config screen directly, with one flat picker that names the mod for anything but vanilla.</p>
 *
 * <p><b>Every row applies only what the author actually changed.</b> That is what makes the
 * portal picker able to bring its own defaults (frame, shape, size - see
 * {@link PortalSpec#withPortal}) without the other rows immediately overwriting them with the
 * values they were showing for the previous portal. It also means editing one row never
 * silently rewrites another, which the unconditional {@code ConfigGroup#save} would otherwise
 * do (it applies every setter on accept, in row order).</p>
 */
public final class PortalCreator {

    private PortalCreator() {
    }

    /** True when there is any portal to show; vanilla alone guarantees it. */
    public static boolean isAvailable() {
        return PortalCatalog.isAvailable();
    }

    /** Opens the portal config screen and, on accept, creates one at the given coords. */
    public static void open(QuestScreen screen, double x, double y) {
        Chapter chapter = screen.getSelectedChapter().orElse(null);
        if (chapter == null) {
            return;
        }
        PortalSpec[] value = {PortalCatalog.defaultsFor(PortalCatalog.defaultPortal())};
        ConfigGroup group = new ConfigGroup("gabrielequests_portal", accepted -> {
            if (accepted) {
                create(chapter, value[0], x, y);
            }
            // The config screen is a full screen; return to the quest editor on close.
            screen.openGui();
        });
        addSpecConfigs(group, "portal", value[0], spec -> value[0] = spec);
        new EditConfigScreen(group).openGui();
    }

    private static void create(Chapter chapter, PortalSpec spec, double x, double y) {
        ChapterImage image = new ChapterImage(0L, chapter);
        image.setImage(new PortalIcon(spec));
        image.setPosition(x, y);
        image.fixupAspectRatio(false);
        NetworkManager.sendToServer(CreateObjectMessage.requestCreation(image));
    }

    /**
     * Adds the spec rows to a config group. Shared between the creation screen and the
     * {@code ChapterImageMixin} properties-screen fix, where {@code portalConfigId} is
     * {@code "image"} so the portal picker <em>replaces</em> the destructive
     * {@code ImageResourceConfig} entry (same LinkedHashMap trick as the item, multiblock,
     * mannequin, entity and structure icons).
     */
    public static void addSpecConfigs(ConfigGroup group, String portalConfigId,
                                      PortalSpec initial, Consumer<PortalSpec> apply) {
        PortalSpec start = initial.normalized();
        PortalSpec[] current = {start};
        Consumer<PortalSpec> update = spec -> {
            current[0] = spec;
            apply.accept(spec);
        };

        // Every portal the install can show; keep the current one selectable even if its mod is
        // gone, so an existing display portal round-trips.
        List<String> portals = new ArrayList<>(PortalCatalog.ids());
        if (!portals.contains(start.portalId())) {
            portals.add(0, start.portalId());
        }
        NameMap<String> portalNames = NameMap.of(start.portalId(), portals)
                .id(id -> id)
                .name(PortalCatalog::label)
                .create();
        group.addEnum(portalConfigId, start.portalId(), id -> {
                    // Only on a real change, and then with that portal's whole structure: an
                    // end portal is a ring of eyed frames, not an obsidian arch with an
                    // end-portal surface inside it.
                    if (!id.equals(start.portalId())) {
                        update.accept(current[0].withPortal(PortalCatalog.defaultsFor(id)));
                    }
                }, portalNames)
                .setNameKey("gabrielequests.portal.portal");

        NameMap<PortalShape> shapes = NameMap.of(start.shape(), List.of(PortalShape.values()))
                .id(PortalShape::id)
                .nameKey(PortalShape::translationKey)
                .create();
        group.addEnum("pt_shape", start.shape(), shape -> {
                    if (shape != start.shape()) {
                        update.accept(current[0].withShape(shape));
                    }
                }, shapes)
                .setNameKey("gabrielequests.portal.shape");

        // Interior size. Which of the three a shape uses is the shape's business (an upright
        // frame is one block thin, a flat ring has no height, the gateway cage is fixed), and
        // normalisation clamps them to what it accepts.
        group.addInt("pt_width", start.width(), w -> {
                    if (w != start.width()) {
                        update.accept(current[0].withWidth(w));
                    }
                }, start.width(), 1, 21)
                .setNameKey("gabrielequests.portal.width");
        group.addInt("pt_height", start.height(), h -> {
                    if (h != start.height()) {
                        update.accept(current[0].withHeight(h));
                    }
                }, start.height(), 1, 21)
                .setNameKey("gabrielequests.portal.height");
        group.addInt("pt_depth", start.depth(), d -> {
                    if (d != start.depth()) {
                        update.accept(current[0].withDepth(d));
                    }
                }, start.depth(), 1, 21)
                .setNameKey("gabrielequests.portal.depth");

        NameMap<String> axes = NameMap.of(start.axis(), List.of("x", "z"))
                .id(a -> a)
                .nameKey(a -> "gabrielequests.portal.axis." + a)
                .create();
        group.addEnum("pt_axis", start.axis(), a -> {
                    if (!a.equals(start.axis())) {
                        update.accept(current[0].withAxis(a));
                    }
                }, axes)
                .setNameKey("gabrielequests.portal.axis");

        // Any block may be the frame - a quartz nether portal is a perfectly good decoration -
        // so this is an item picker rather than a closed list. Blocks without an item (or an
        // empty pick) leave the frame alone.
        group.addItemStack("pt_frame", stackOf(start.frameId()), stack -> {
                    String id = blockIdOf(stack);
                    if (id != null && !id.equals(start.frameId())) {
                        update.accept(current[0].withFrame(id));
                    }
                }, ItemStack.EMPTY, false, false)
                .setNameKey("gabrielequests.portal.frame");

        group.addBool("pt_lit", start.lit(), b -> {
                    if (b != start.lit()) {
                        update.accept(current[0].withLit(b));
                    }
                }, true)
                .setNameKey("gabrielequests.portal.lit");
        group.addBool("pt_corners", start.corners(), b -> {
                    if (b != start.corners()) {
                        update.accept(current[0].withCorners(b));
                    }
                }, true)
                .setNameKey("gabrielequests.portal.corners");
        group.addBool("pt_base", start.base(), b -> {
                    if (b != start.base()) {
                        update.accept(current[0].withBase(b));
                    }
                }, false)
                .setNameKey("gabrielequests.portal.base");
        group.addBool("pt_decor", start.decor(), b -> {
                    if (b != start.decor()) {
                        update.accept(current[0].withDecor(b));
                    }
                }, false)
                .setNameKey("gabrielequests.portal.decor");
        group.addBool("pt_eyes", start.eyes(), b -> {
                    if (b != start.eyes()) {
                        update.accept(current[0].withEyes(b));
                    }
                }, true)
                .setNameKey("gabrielequests.portal.eyes");
        group.addBool("pt_view_rotate", start.viewRotate(), b -> {
                    if (b != start.viewRotate()) {
                        update.accept(current[0].withViewRotate(b));
                    }
                }, false)
                .setNameKey("gabrielequests.portal.view_rotate");

        // The orbit angles, typeable instead of only draggable. Changed-only like every other
        // row here, so picking another portal (whose defaults keep the current view) is not
        // undone by these two.
        group.addInt("pt_yaw", start.yaw(), v -> {
                    if (v != start.yaw()) {
                        update.accept(current[0].withRotation(v, current[0].pitch()));
                    }
                }, PortalSpec.DEFAULT_YAW, 0, 359)
                .setNameKey("gabrielequests.display.yaw");
        group.addInt("pt_pitch", start.pitch(), v -> {
                    if (v != start.pitch()) {
                        update.accept(current[0].withRotation(current[0].yaw(), v));
                    }
                }, PortalSpec.DEFAULT_PITCH, -89, 89)
                .setNameKey("gabrielequests.display.pitch");
    }

    /** The item form of a block id, for the frame picker; empty when the block has no item. */
    private static ItemStack stackOf(String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.BLOCK.get(rl));
    }

    /** The block id behind a picked item, or {@code null} when the item places no block. */
    @Nullable
    private static String blockIdOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Block block = Block.byItem(stack.getItem());
        if (block == Blocks.AIR) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
