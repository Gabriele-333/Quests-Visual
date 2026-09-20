package net.gabriele333.gabrielequests.client;

import dev.architectury.networking.NetworkManager;
import dev.ftb.mods.ftblibrary.config.ConfigGroup;
import dev.ftb.mods.ftblibrary.config.NameMap;
import dev.ftb.mods.ftblibrary.config.ui.EditConfigScreen;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.net.CreateObjectMessage;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.ChapterImage;
import net.gabriele333.gabrielequests.client.entity.EntityDisplayRenderer;
import net.gabriele333.gabrielequests.client.entity.EntityIcon;
import net.gabriele333.gabrielequests.client.entity.EntitySpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Display entity" = a decorative {@link ChapterImage} whose icon is an {@link EntityIcon},
 * mirroring {@link MannequinCreator}/{@link MultiblockCreator}: build client-side, hand it
 * to the server with {@code CreateObjectMessage}; the icon serialises to a short string and
 * is rebuilt by the {@code IconMixin} on {@code Icon.getIcon}.
 *
 * <p>The entity is chosen through an FTB Library config screen with a single searchable
 * picker listing <em>every</em> registered entity type (vanilla plus whatever mods are
 * installed). The resulting model is always 3D and always orbit-rotatable (see
 * {@link EntityIcon}). Unrenderable or uncreatable types simply fall back to a placeholder
 * box, so offering the whole registry never crashes anything.</p>
 */
public final class EntityCreator {

    private EntityCreator() {
    }

    /** Opens the entity config screen and, on accept, creates one at the given coords. */
    public static void open(QuestScreen screen, double x, double y) {
        Chapter chapter = screen.getSelectedChapter().orElse(null);
        if (chapter == null) {
            return;
        }
        EntitySpec[] value = {EntitySpec.defaultSpec()};
        ConfigGroup group = new ConfigGroup("gabrielequests_entity", accepted -> {
            if (accepted) {
                create(chapter, value[0], x, y);
            }
            // The config screen is a full screen; return to the quest editor on close.
            screen.openGui();
        });
        addSpecConfigs(group, "type", value[0], spec -> value[0] = spec);
        new EditConfigScreen(group).openGui();
    }

    private static void create(Chapter chapter, EntitySpec spec, double x, double y) {
        ChapterImage image = new ChapterImage(0L, chapter);
        image.setImage(new EntityIcon(spec));
        image.setPosition(x, y);
        makeDefaultSize(image);
        NetworkManager.sendToServer(CreateObjectMessage.requestCreation(image));
    }

    /** The animation-speed multipliers offered by the picker (0 = paused, 1.0 = real-time). */
    private static final List<String> SPEEDS =
            List.of("0.0", "0.25", "0.5", "0.75", "1.0", "1.5", "2.0", "3.0", "4.0");

    /**
     * Adds the entity-type picker, the animation-speed picker and the view angles to a config
     * group. Shared
     * between the creation screen and the {@code ChapterImageMixin} properties-screen fix,
     * where {@code entityConfigId} is {@code "image"} so the type picker <em>replaces</em> the
     * destructive {@code ImageResourceConfig} entry (same LinkedHashMap trick as the item,
     * multiblock and mannequin icons); the speed row uses a fresh {@code ent_*} id.
     *
     * <p>{@code ConfigGroup#save} applies every setter in row order on accept; each folds its
     * field into a running spec and hands the result to {@code apply}, so after the last row
     * the applied spec carries all edits.</p>
     */
    public static void addSpecConfigs(ConfigGroup group, String entityConfigId,
                                      EntitySpec initial, Consumer<EntitySpec> apply) {
        EntitySpec[] current = {initial.normalized()};
        Consumer<EntitySpec> update = spec -> {
            current[0] = spec;
            apply.accept(spec);
        };
        // Every registered entity type, id-sorted; keep the current one selectable even if
        // its mod is gone so an existing display entity round-trips.
        List<String> ids = new ArrayList<>();
        BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                .map(ResourceLocation::toString)
                .sorted()
                .forEach(ids::add);
        if (!ids.contains(current[0].entityId())) {
            ids.add(0, current[0].entityId());
        }
        NameMap<String> nameMap = NameMap.of(current[0].entityId(), ids)
                .id(s -> s)
                .nameKey(EntityDisplayRenderer::descriptionKey)
                .create();
        group.addEnum(entityConfigId, current[0].entityId(),
                        id -> update.accept(current[0].withEntity(id)), nameMap)
                .setNameKey("gabrielequests.entity.type");

        String speed = closestSpeed(current[0].speed());
        NameMap<String> speedMap = NameMap.of(speed, SPEEDS)
                .id(s -> s)
                .nameKey(s -> "gabrielequests.entity.speed." + s)
                .create();
        group.addEnum("ent_speed", speed,
                        s -> update.accept(current[0].withSpeed(Float.parseFloat(s))), speedMap)
                .setNameKey("gabrielequests.entity.speed");

        // The orbit angles, typeable instead of only draggable. Each row folds into the
        // running spec (yaw first, then pitch), so setting both in one screen works.
        group.addInt("ent_yaw", current[0].yaw(),
                        v -> update.accept(current[0].withRotation(v, current[0].pitch())),
                        EntitySpec.DEFAULT_YAW, 0, 359)
                .setNameKey("gabrielequests.display.yaw");
        group.addInt("ent_pitch", current[0].pitch(),
                        v -> update.accept(current[0].withRotation(current[0].yaw(), v)),
                        EntitySpec.DEFAULT_PITCH, -89, 89)
                .setNameKey("gabrielequests.display.pitch");
    }

    /** The preset speed string closest to a stored speed (so the row reflects the real value). */
    private static String closestSpeed(float speed) {
        String best = "1.0";
        float bestDiff = Float.MAX_VALUE;
        for (String preset : SPEEDS) {
            float diff = Math.abs(Float.parseFloat(preset) - speed);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = preset;
            }
        }
        return best;
    }

    /** Gives a fresh display-entity image a visible 2x2 default footprint; harmless if it fails. */
    private static void makeDefaultSize(ChapterImage image) {
        try {
            setDouble(image, "width", 2.0D);
            setDouble(image, "height", 2.0D);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Non-fatal: keep the default size, the user can resize in the properties screen.
        }
    }

    private static void setDouble(ChapterImage image, String field, double value)
            throws ReflectiveOperationException {
        Field f = ChapterImage.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setDouble(image, value);
    }
}
