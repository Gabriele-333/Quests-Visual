> **Migration note (2026-07-04).** This document was written when the code lived inside
> FMTT2, in the `queststools` package. It is now the standalone **GabrieleQuests** mod:
> - package `net.gabriele333.fmtt2.queststools.*` → **`net.gabriele333.gabrielequests.*`**
>   (the `queststools` segment is gone; e.g. `queststools/client/multiblock/` →
>   `gabrielequests/client/multiblock/`);
> - identifiers `fmtt2.*` → **`gabrielequests.*`**, icon prefixes `fmtt2-multiblock:` /
>   `fmtt2-mannequin:` → **`gabrielequests-multiblock:` / `gabrielequests-mannequin:`**;
> - mixin config `fmtt2-queststools.mixins.json` → **`gabrielequests.mixins.json`**.
> For the current state, read the source. The rest of this text is historical.

# QuestsTools (inside FMTT2) — Functional specification (version-agnostic)

> **Provenance note.** These features started life as a standalone mod named **QuestsTools**
> (package `net.saturnx.queststools`) and were then **ported into FMTT2** under
> `net.gabriele333.fmtt2.queststools` (sub-packages `client` and `mixin`), with the mixin
> config **`fmtt2-queststools.mixins.json`** and translation keys renamed from
> `queststools.*` to `fmtt2.*`. Everything else (behaviour, hook points) is unchanged from
> the original mod.

This file is the **source of truth** for *what* FMTT2's quest-editor tools do and *where* they
hook into FTB Quests. It is written to be independent of any single Minecraft version: the
concrete implementation (packages, mappings, versions) changes from one version to the next,
but the **behaviour** and the **logical hook points** described here stay the same. Porting the
features to a new version is a matter of remapping the names listed here and recompiling.

The "queststools" feature set is a **client-side add-on for FTB Quests**: it adds tools to the
quest editor. It adds no blocks, items, recipes or server logic (the rest of FMTT2 does, but
that is out of scope for this document).

---

## 1. Features

### 1.1 Box selection with Shift + drag (implemented)

**User-facing goal.** In the quest editor (with edit mode active), holding **Shift** and
dragging with the **left mouse button** over empty space in the panel draws a **selection
rectangle**. On release, every quest / link / image whose centre falls inside the rectangle
becomes selected.

**Expected behaviour, point by point:**

1. It must work **only** when editing is allowed (`file.canEdit()`), exactly as the existing
   middle-button selection does.
2. The drag must start on **empty space**. Shift+click *on a quest* keeps FTB Quests' original
   behaviour (add/remove that single quest from the selection), because that click is consumed
   by the child widget before it ever reaches the panel.
3. While dragging with Shift the view must **not pan** (left button without Shift keeps
   panning, as before).
4. Holding **Ctrl** on release makes the new selection **add to** the existing one instead of
   replacing it (behaviour already present in `selectAllQuestsInBox`, which we reuse wholesale).
5. If the user releases Shift *mid-drag*, the operation still counts as a box selection (the
   intent is "latched" at press time — see §3).

**Key implementation idea.** FTB Quests **already has** a complete box selection, but bound to
the **middle mouse button**. QuestsTools reimplements nothing: it makes *Shift + left button*
trigger exactly the same code path (box rendering + `selectAllQuestsInBox`), minimising friction
with any internal FTB Quests changes.

### 1.2 Set shape on a multi-selection (implemented)

**User-facing goal.** With one or more quests selected, the context menu (right click on a
quest) gains a **"Set shape"** entry: it opens a sub-menu with every available shape and,
picking one, applies it **to all selected quests at once**.

**Expected behaviour, point by point:**

1. The targets are **all selected quests**; if nothing is selected, the target is the single
   quest that was right-clicked.
2. The available shapes are the ones in FTB Quests' registry (`QuestShape.idMapWithDefault`), so
   it automatically includes shapes added by resource packs plus the special **"default"** entry
   (use the chapter's default shape).
3. Applying a shape is exactly equivalent to changing it from the quest's config: it writes the
   `shape` field and sends an `EditObjectMessage` to **persist and sync** through the server
   (one per quest).
   **Mind the semantics of "default":** `Quest.getShape()` returns
   `shape.isEmpty() ? chapter.getDefaultQuestShape() : shape`, so "inherit the default" is the
   **empty string `""`**, NOT the string `"default"`. Setting the literal `"default"` goes
   through `QuestShape.get` → `getOrDefault(id, defaultShape)`, where `defaultShape` is the
   *first* shape in the map (arbitrary order) → wrong shape, and possibly a white background if
   that shape has no texture. The menu therefore uses `QuestShape.idMap` (real shapes) plus an
   explicit "Default" entry that writes `""`.
4. The entry only shows up where the object menu already shows up, i.e. in edit mode.
5. **No `ContextMenuItem.subMenu` (FTB Library bug):** FTB Library's "stacked" sub-menu positions
   the new menu using `button.getPosX()` (coordinates *relative* to the parent menu) as if they
   were absolute screen coordinates → the sub-menu appears in the top-left corner and the click
   looks like it did nothing. Tellingly, FTB Quests never uses `subMenu` in production: its own
   "properties" entry (`QuestScreen#openPropertiesSubMenu`) closes the current menu and opens a
   **new** context menu at the mouse position (`BaseScreen.openContextMenu(List)`), with a
   non-clickable title entry (`ContextMenuItem.title(...)`) + `SEPARATOR` at the top. "Set shape"
   replicates that flow exactly: click the entry → close the menu → open the shape picker at the
   mouse.

### 1.3 Display Item (an item as an "image", with its model) (implemented)

**User-facing goal.** Being able to place an **item rendered with its own model** (blocks in 3D,
items with their sprite) on the quest map as a decorative element, without using a flat texture
and without creating an empty quest.

**How it works (an important design decision).** It is not a new serialised object type — that
would be huge and brittle. It is a **`ChapterImage` whose icon is an `ItemIcon`**:
`ChapterImage.image` is an `Icon`, and `ItemIcon` renders the item's model. That reuses the whole
existing image infrastructure (position, size, rotation, saving, sync, moving, selection, box
selection).

**Flow.** The right-click menu on empty space gains **"Add display item"** (next to "Add image"):
it opens FTB Library's item picker (`SelectItemStackScreen`) and, on confirm, creates the
ChapterImage:
```
ChapterImage img = new ChapterImage(chapter);
img.setImage(ItemIcon.getItemIcon(stack));
img.setPosition(questX, questY); img.fixupAspectRatio(false);
chapter.addImage(img);
NetworkManager.sendToServer(EditObjectMessage.forQuestObject(chapter)); // re-serialises the chapter
```
Persistence comes for free: the image is saved as `image.toString()` (the string `item:<id>`) and
rebuilt with `Icon.getIcon(String)`. *Known limit:* items with complex NBT/components may not
round-trip perfectly (it depends on `ItemIcon.toString`).

**Mandatory render-state fix (white outline).** Rendering the item model (`ItemIcon.draw` →
`GuiHelper.drawItem` → vanilla `GuiGraphics.renderItem`) ends the batch with the
`entity_solid`/`entity_cutout` render types, whose teardown **disables blending**. FTB Library's
`ImageIcon.draw`/`GuiHelper.drawTexturedRect` never re-enable blending, and
`QuestPanel.addWidgets` adds images **before** quests: the first quest drawn after a display item
rendered the transparent pixels of its shape texture as opaque white (the "white outline"). The
fix is a mixin on `ChapterImageButton.draw` that, right after every `Icon.draw` (one occurrence
per align-to-corner/centred branch), calls `GuiHelper.setupDrawing()` to restore blend + shader
colour. It has to be done at the rendering level (rather than by wrapping the icon at creation
time) because after a save/reload the icon is rebuilt as a plain `ItemIcon`.

**Mandatory fix: a readable name instead of `ItemIcon.toString()`.** `ItemIcon.toString()`
serialises the item's **entire data-component map** (`item:<id> <count> <damage> <nbt...>`) —
that is the icon's persistence format and **must not be touched**. FTB Quests, however, also uses
it as UI text in three places, and with an item icon the result is an enormous string:
- `ChapterImage.getTitle()` → the context menu's title row (`ChapterImageButton.onClicked`); the
  menu sizes itself on its widest row → **a menu wider than the screen, with unusable entries**;
- `ChapterImageButton.openEditScreen` → the properties screen title;
- the delete confirmation (`setYesNoText`, key `delete_item`).

Fix: when `Icon.getIngredient()` returns an `ItemStack` (true both for a freshly created
`ItemIcon` and for the `LazyIcon` wrapper produced by `Icon.getIcon("item:...")` after a
save/reload, which delegates), substitute the string with `stack.getHoverName()`.

**Mandatory fix: the properties screen destroyed the icon (the "vanished image").**
`ChapterImage.fillConfigGroup` registers the `image` property as an `ImageResourceConfig` (a
texture `ResourceLocation`) with the setter `v -> setImage(Icon.getIcon(v))`. On confirm,
`ConfigGroup.save(true)` applies **every** setter unconditionally: so editing any property
(width, height, ...) also re-applied `image`, replacing the item icon with a non-existent texture
icon (`ItemIcon` implements `IResourceIcon` and reports the item's **registry id** as its
resource location → it becomes an `AtlasSpriteIcon` for a sprite that does not exist; after a
reload the `LazyIcon` is not even an `IResourceIcon` → `ftblibrary:none` → empty icon). Net
result: the display item vanished as soon as you accepted the properties screen.
Fix: inject at TAIL of `fillConfigGroup`; if the icon is an item, re-register `image` under the
**same id** (`ConfigGroup` keeps its values in a `LinkedHashMap`: putting the same id replaces
the entry while keeping its position in the GUI) as an `ItemStackConfig` with the setter
`s -> setImage(ItemIcon.getItemIcon(s))`. Resizing now works and, as a bonus, the "image" row
becomes an item picker for changing the displayed item.

### 1.4 Mekanism Multiblock Display (implemented)

**User-facing goal.** Place a **multiblock rendered in 3D** on the quest map as a decorative
element, with configurable size, glass and **blocks**. Supported types (the `MultiblockType`
enum, one generator per type): **Mekanism** Dynamic Tank, Induction Matrix, Thermoelectric
Boiler, Thermal Evaporation Plant; **Mekanism Generators** Fission Reactor, Industrial Turbine
(odd square base 5–17, central rotor + rotational complex, pressure-disperser layer, coils
connected above the complex, vents only from that level upwards — the blades are items inside the
rotors, not blocks, so they are not part of the layout), Fusion Reactor (fixed 5×5×5, per-face
cross pattern taken from the validator's `ALLOWED_GRID`, controller on top of the centre);
**AE2** ME Crafting CPU (solid cuboid 1–16 per axis, ≥1 storage guaranteed by the pattern) and
Quantum Network Bridge (vertical 3×3 ring with the link chamber at the centre), both shown in
their **formed/active state**: the blockstates carry `formed=true`/`powered=true` (a generic
helper that sets properties by name, a no-op when they are absent) and the connectivity of the
formed models — which in game comes from the block entities (`CraftingCubeModelData.CONNECTIONS`,
`QuantumBridgeBlockEntity.FORMED_STATE`) — is computed from the generated structure in
`Ae2ModelData` and passed to the tessellator as ModelData. That is also why AE2 is a
**compileOnly** dependency (no hard dependency: the class is only loaded behind
`ModList.isLoaded("ae2")`, and a failing hook degrades to the unconnected formed look);
**Ender IO** Capacitor Bank (cuboid 1–8, banks of the same tier always connect; the connected
textures come from **Athena**, which resolves neighbours through our fake level — Athena must be
present or the bank models will not load at all); **Ars Nouveau** Enchanting Apparatus (arcane
core on the ground with the apparatus above it + 8 arcane pedestals in a ring, configurable
radius 1–3: `EnchantingApparatusTile.pedestalList(pos, 3, level)` accepts pedestals anywhere
within 3 blocks; core and pedestals are ordinary baked models, the apparatus is GeckoLib → BER
phase).

Menu entry **"Add display multiblock"** next to "Add display item" (shown only if at least one
type has its blocks registered). **Two-level selection:** first a context menu with the
**available mods** (`MultiblockType.modGroup()` — "mekanism" also covers Generators; icon = a
representative item; same fresh-menu-at-the-mouse flow as ShapeMenu, never
`ContextMenuItem.subMenu`; with a single mod the menu is skipped), then the FTB config screen
with **only that mod's types** (type, structure size, glass, and only its option rows), and on
confirm the ChapterImage is created. The properties screen of an existing ChapterImage keeps the
full picker with every type.

**Per-type options (`MultiblockType.Option`).** Block customisation beyond size/glass, always as
**closed lists of validator-valid choices** (never a free block picker, which would break the
validity guarantee): Induction Matrix → cell and provider tier (basic/advanced/elite/ultimate);
Boiler → superheating element layers (1–3, clamped to `h-4`); Fission Reactor → core layout
(checkerboard / solid / single column); Crafting CPU → storage tier (1k–256k) and co-processor
count (none/some/many); Capacitor Bank → tier (basic/advanced/vibrant). They are serialised as
extra `key=value` pairs in the spec string (old strings without options load with the defaults;
unknown keys are dropped at parse time).
In the UI the option rows show the **union** of the available types: a row only has an effect if
its type is selected, because the type row is applied first (insertion order) and `normalized()`
drops options the final type does not declare.
The render framing is computed by projecting the box's 8 corners through the current rotation
(a square orthographic window centred on the projected bounds + 6% padding): the structure can
never overflow the image, and elongated builds fill the frame.

**Orbital rotation (hold left click).** In edit mode, holding the **left button** on a multiblock
display and dragging rotates it in 3D around its centre (drag horizontally = yaw, vertically =
pitch, clamped to ±89°). The gesture occupies exactly the case FTB does **not** consume (a plain
left click on an image with no `click` action → it would fall through to panning the map), so
Alt+click (move), Shift/Ctrl (selection), `click` actions and the right-click menu all stay
intact. Flow: press → `MultiblockOrbitDrag.begin` (a RETURN-cancellable mixin on
`ChapterImageButton.mousePressed`, consuming only the "return false" case); every frame the
button's `draw` updates the angles and `MultiblockIcon.draw` uses `MultiblockRenderer.preview(...)`
(a **single** reused FBO target, re-tessellated only when the angle changes by ≥1° — the LRU
cache is left untouched); release → `QuestPanelMixin.mouseReleased` TAIL calls `commit()`: the
final angles go into the spec (`rot=<yaw>x<pitch>`, defaulting to 225x30 for old strings) and the
chapter is re-sent with `EditObjectMessage` → the rotation **persists and syncs** like any other
edit. The framing formula is identical between preview and cache → no jump on release.

**Design (three pillars, in order of importance):**

1. **Valid by construction, verified only once.** The structures are not read from the world:
   they are **generated procedurally** (`MekanismMultiblock`, one generator per type) following
   the rules of Mekanism's validators as verified against the 1.21.x sources (casing frame on the
   edges, glass only on the faces, cuboids 3–18, boiler with a full pressure-disperser layer
   above the superheating elements → minimum height 5, evaporation plant fixed at 4×4 with the
   top corners ignored and a single controller, fission with fuel-assembly columns + control rod
   on top → minimum height 4). User-supplied sizes go through `MultiblockSpec.normalized()`
   (per-type clamp) **only at creation, at parse time and when edited in the config** — never
   during rendering.
2. **No lag.** `MultiblockRenderer` renders the structure **exactly once** into a dedicated
   off-screen framebuffer (512², with its own depth buffer) and caches it per spec (LRU, max 8,
   `destroyBuffers` on eviction); the per-frame draw is **a single textured quad**, whatever the
   size of the structure. The tessellator uses a fake level (`StructureRenderLevel`, full-bright,
   vanilla directional shading) that enables **face culling between adjacent blocks**; two passes
   (opaque then translucent) for the glass. Every piece of global state it touches (projection,
   model-view, fog, shader colour, scissor, framebuffer) is saved and restored.
3. **No crashes / no data loss.** Blocks are resolved **by registry name only** (no compile
   dependency on Mekanism): if they are missing (Mekanism/Generators absent) or the render fails,
   the outcome is cached and the icon draws a flat placeholder — the failure is logged once and
   never retried. If the serialised string cannot be parsed, `MultiblockIcon` keeps it
   **verbatim** (`toString()` returns it unchanged), so a save/sync cycle never destroys data.

**Persistence (same scheme as the display item).** The icon serialises to
`fmtt2-multiblock:<type>,<w>x<h>x<d>,glass=<0|1>` via `toString()`; reconstruction goes through a
mixin at the **HEAD of `Icon.getIcon(String)`** (FTB Library has no registry for custom prefixes),
*before* the splits on `" + "` (combined icons) and `";"` (properties) — the format avoids both
separators anyway. The mixin lives in the **common** list: the server reads and re-serialises
chapters, and without the mixin the string would be parsed as a texture and overwritten on the
next save. `MultiblockIcon` is deliberately loadable server-side (common-only fields; GL/GUI are
only reached inside `draw`).

**Config/properties.** On the ChapterImage properties screen the `image` row (destructive, see
§1.3) is replaced by the **type picker** and the width/height/depth/glass rows are added
(`MultiblockCreator.addSpecConfigs`, shared with the creation flow): every setter re-normalises
the spec → validation happens on every edit. The context menu / properties screen / delete
confirmation titles use `MultiblockIcon.displayName()` ("Dynamic Tank 5×5×5").

**Known limits (v1).** No blocks with a BlockEntityRenderer (the supported Mekanism multiblock
blocks are static models); after a resource pack change the cached texture can show the old
models until the cache rotates; the sliders show 3–18 for every type but the per-type clamp
(e.g. 4×4 for the evaporation plant) is applied on save; orbital rotation is only available in
edit mode (it persists through `EditObjectMessage`, which non-editors cannot send).

**Later extensions (read the source for the current, complete list).** The serialised format grew
to `fmtt2-multiblock:<type>,<w>x<h>x<d>,glass=<0|1>,view=<0|1>,rot=<yaw>x<pitch>[,<opt>=<choice>...]`;
types with a **BlockEntityRenderer** were added (sandboxed BER phase: Draconic Reactor, Energy
Core, Ars Nouveau Enchanting Apparatus), along with the AE2/Ender IO types, the per-type options,
**view-only** rotation for non-editors (`viewRotate`) and Draconic **Fusion Crafting** (static
render: core + injectors on the axes at distance 3–5 with `facing` towards the core; DE's
validator requires a cardinal distance > `fusionInjectorMinDist=2`, so the smallest buildable one
is 3). Orbital rotation is now a shared service (`OrbitDraggable` + `DisplayOrbitDrag`) used by
the Mannequin Display too (§1.5).

### 1.5 Mannequin Display (a 3D player mannequin) (implemented)

A `ChapterImage` whose icon is a **`MannequinIcon`** drawing a player mannequin in 3D, **always
rotatable** (orbital drag for editors and non-editors alike; in edit mode it persists). Same
persistence scheme as the display item/multiblock: `toString()` →
`fmtt2-mannequin:head=<id>,chest=<id>,legs=<id>,feet=<id>,main=<id>,off=<id>,rot=<yaw>x<pitch>`,
rebuilt by the same **common** mixin on `Icon.getIcon(String)` (a branch added next to the
multiblock one). The spec is common-safe (only `String`/`int`; no validation against the item
registry → a robust round-trip between different mod sets, with a slot resolving to empty when
the item is missing).

**Render.** `MannequinRenderer` builds a fake `RemotePlayer` with the local player's
`GameProfile` (→ **the player's skin**, slim/classic model included), reused between frames; it
sets the equipment from the 6 slots and draws it with
`InventoryScreen.renderEntityInInventory` (projection/lighting/GUI state restoration all handled
by vanilla). No FBO, no cache: one entity render per frame is cheap and leaves rotation free.
Rigid turntable (body+head at `180+yaw`, pitch from the quaternion pose). Everything in
try/catch → placeholder, never a crash.

**Config/properties.** The "Add display mannequin" entry is always available (it only uses
vanilla's player render, no mod dependency). The config screen has one `ItemStackConfig` per slot
(helmet/chestplate/leggings/boots/main hand/off hand); when editing, the destructive `image` row
is replaced by the helmet picker and the other slots are `mann_*` rows
(`MannequinCreator.addSpecConfigs`, shared between creation and editing). Package
`queststools/client/mannequin/` (+ `MannequinCreator` in `queststools/client/`).

### 1.6 Future features (not implemented yet)

Listed here so that adding them later does not break this document's structure:

- (placeholder) aligning/distributing the selected quests;
- (placeholder) bulk duplication of the selection;
- (placeholder) a configurable key/modifier instead of Shift;
- (placeholder) more multiblocks from other mods: add a constant to `MultiblockType` (already
  generalised beyond Mekanism) with a generator that conforms to the target mod's validator;
- (placeholder) a mannequin with an arbitrary player's skin (by name, not just the local one)
  and/or selectable poses.

---

## 2. Hook points in FTB Quests

> ⚠️ **Package names change between versions.** A real example already observed:
> `MouseButton` lives in `dev.ftb.mods.ftblibrary.ui.input` on the **2101.x** line (MC 1.21.1)
> but was moved to `dev.ftb.mods.ftblibrary.client.gui.input` on more recent branches. **Always**
> check against the actual jar (`javap`) before taking a name for granted. The table in §5 tracks
> these differences.

All the classes are in FTB Quests' *common* module, package
`dev.ftb.mods.ftbquests.client.gui.quests`.

### `QuestScreen` (extends `BaseScreen`)
Relevant fields:
- `MouseButton grabbed` — the button currently "grabbed" on the panel (null if none).
- `int prevMouseX, prevMouseY` — the anchor point, set on press; used both for panning and as the
  corner of the selection rectangle.
- `List<Movable> selectedObjects` — the selected objects.

Relevant methods:
- `void drawBackground(GuiGraphics, Theme, int x, int y, int w, int h)` — every frame, if
  `grabbed != null`, decides what to do:
  - `if (grabbed.isLeft())` → **pan** the view;
  - `else if (grabbed.isMiddle())` → **draw the selection rectangle**.
- `void selectAllQuestsInBox(int mouseX, int mouseY, double scrollX, double scrollY)` — selects
  every `QuestPositionableButton` whose centre falls inside the rectangle defined by
  `prevMouseX/Y` and `mouseX/Y`. If Ctrl is **not** held, it clears `selectedObjects` first.

### `QuestPanel` (extends `Panel`)
- `boolean mousePressed(MouseButton button)` — when the click lands on empty space (the child
  widgets do not consume it), for the left or middle button it sets
  `prevMouseX/Y = getMouseX/Y()` and `grabbed = button`.
- `void mouseReleased(MouseButton button)` — if
  `grabbed != null && grabbed.isMiddle() && file.canEdit()`, it calls `selectAllQuestsInBox(...)`;
  then it clears `grabbed`.

### `dev.ftb.mods.ftblibrary.ui.input.MouseButton`
- `boolean isLeft()`, `boolean isMiddle()`, `boolean isRight()`; static constants `LEFT`,
  `MIDDLE`, `RIGHT`, ...

### Context menu and shape editing (feature §1.2)
- **Two distinct menu paths** (important!): right-clicking a quest goes through
  `QuestButton.onClicked(MouseButton)`, which:
  - if **exactly one** object is selected → uses `ContextMenuBuilder` →
    `QuestScreen.addObjectMenuItems(...)` (the per-object menu);
  - if **several** objects are selected → builds a multi-selection menu **inline** (entries
    "clear rewards", **"bulk_change_size"**, "delete", ...) and opens it with
    `BaseScreen.openContextMenu(List)`, **without** going through `addObjectMenuItems`.
  To cover both cases, the "Set shape" entry has to be added in **both**.
- `QuestScreen.addObjectMenuItems(List<ContextMenuItem>, Runnable gui, QuestObjectBase object, Movable deletionFocus)`
  — the single-object menu. It has access to `selectedObjects`.
- `QuestScreen.selectedObjects` : `List<Movable>` — the current selection
  (`Quest implements Movable`).
- `Quest.getShape()` (String) + the **private** `shape` field (no public setter → a Mixin accessor
  is required).
- `dev.ftb.mods.ftbquests.quest.QuestShape` : `static NameMap<String> idMapWithDefault` (shape ids
  + "default"), `static QuestShape get(String)` (it is an `Icon`). `NameMap` exposes `keys`
  (List<String>), `getDisplayName(id)`, `getIcon(id)`.
- Persistence: `EditObjectMessage.sendToServer(QuestObjectBase)` (equivalent to
  `Play2ServerNetworking.send(EditObjectMessage.forQuestObject(obj))`), the same one the editor
  config uses after `shape = v`.
- Shape picker menu: do **not** use `ContextMenuItem.subMenu(...)` (broken positioning, see §1.2
  point 5); use `BaseScreen.openContextMenu(List)` from the entry's callback, with
  `ContextMenuItem.title(Component)` + `ContextMenuItem.SEPARATOR` as the header.

### Icon deserialisation / multiblock display (feature §1.4)
- `dev.ftb.mods.ftblibrary.icon.Icon.getIcon(String)` (static, **common**): the single point where
  icons are rebuilt from a string. It has no registry for custom prefixes; it splits on `" + "`
  (CombinedIcon) and then on `";"` (IconProperties), so the hook has to be at **HEAD**, before
  both splits. `ChapterImage` saves the icon as `image.toString()` both to SNBT (`writeData`) and
  over the network, and **the server too** goes through here when re-reading chapters: the hook
  must live in the mixins' common list.
- `Icon` is abstract with `draw(GuiGraphics,int,int,int,int)` (from `Drawable`); subclasses are
  loadable on a dedicated server as well, provided the client code stays inside method bodies that
  are never invoked server-side (the same pattern as FTB's `ImageIcon`).
- Off-screen rendering: `TextureTarget(int,int,boolean,boolean)`,
  `RenderSystem.backupProjectionMatrix()/restoreProjectionMatrix()`,
  `RenderSystem.getModelViewStack()` (a JOML `Matrix4fStack`) + `applyModelViewMatrix()`,
  `ModelBlockRenderer.tesselateBlock(BlockAndTintGetter, BakedModel, BlockState, BlockPos,
  PoseStack, VertexConsumer, boolean, RandomSource, long, int, ModelData, RenderType)` (the
  NeoForge overload), `Minecraft.renderBuffers().bufferSource()` (shared: call `GuiGraphics.flush()`
  before switching framebuffer), blit with `RenderSystem.setShaderTexture(int, int glId)` +
  `BufferUploader.drawWithShader` (V flipped: FBO textures are bottom-up). The panel clipping's
  scissor test must be disabled during the FBO pass (`GL11.glIsEnabled` + `GlStateManager`).
- Config UI: `ConfigGroup(String, ConfigCallback)` + `EditConfigScreen(ConfigGroup)`;
  `ConfigGroup.addEnum(id, value, setter, NameMap)` / `addInt(id, v, setter, def, min, max)` /
  `addBool`; `NameMap.of(def, List).id(...).nameKey(...).create()`;
  `ConfigValue.setNameKey(String)` for the labels.

### Creating images / display items (feature §1.3)
- `QuestPanel.mousePressed` builds the right-click-on-empty-space menu in a local `List` and opens
  it with `QuestScreen.openContextMenu(List)` (the only occurrence in the method). The quest
  coordinates are in `QuestPanel`'s `protected double questX, questY` fields;
  `private final QuestScreen questScreen`.
- `ChapterImage(Chapter)` + `setImage(Icon)` + `setPosition(double,double)` +
  `fixupAspectRatio(boolean)`; `Chapter.addImage(ChapterImage)`; then
  `NetworkManager.sendToServer(EditObjectMessage.forQuestObject(chapter))`.
- `QuestScreen.getSelectedChapter()` : `Optional<Chapter>` (public getter) and
  `BaseScreen.openGui()` to reopen the editor after the picker.
- Picker: `dev.ftb.mods.ftblibrary.config.ItemStackConfig(boolean isFixedSize, boolean allowEmpty)`
  + `dev.ftb.mods.ftblibrary.config.ui.resource.SelectItemStackScreen(ItemStackConfig, ConfigCallback)`;
  `ConfigCallback.save(boolean)`; the chosen value is read with `ItemStackConfig.getValue()`.
- `ItemIcon.getItemIcon(ItemStack)` : an `Icon` that draws the item's model.
- For the name/properties fixes: `Icon.getIngredient()` (an `ItemStack` for item icons, including
  through the delegating `LazyIcon`; `null` for other types), `ChapterImage.getTitle()` +
  `fillConfigGroup(ConfigGroup)` (client-only), `ChapterImage.getImage()/setImage(Icon)`,
  `ConfigGroup.add(id, ...)` (a put on a `LinkedHashMap` → re-registering the same id replaces the
  entry), `ChapterImageButton.openEditScreen` (private; its only local is `String name`, with the
  LVT present in the 2101.1.27 jar) and the single invoke of
  `ContextMenuItem.setYesNoText(Component)` in `ChapterImageButton.onClicked`.

---

## 3. Mixin strategy (1.21.1 implementation)

Libraries: **Mixin** + **MixinExtras** (both provided by NeoForge at runtime and at compile time).
No target is a Minecraft class, so **no refmap is needed**: FTB Quests/Library names are stable
between dev and production within the same version.

Shared state: `net.gabriele333.fmtt2.queststools.client.BoxSelectState.active` (a static boolean,
render thread only). It means "a Shift+left box selection is in progress".

**`QuestPanelMixin`** (target `QuestPanel`):
1. `@Inject(HEAD)` on `mousePressed` → `active = false` (every press starts clean).
2. `@Inject(FIELD PUTFIELD grabbed, shift=AFTER)` on `mousePressed` → right after `grabbed = button`,
   if `button.isLeft() && Screen.hasShiftDown()` then `active = true`. Injecting here guarantees it
   only activates on the "grab on empty space" path.
3. `@ModifyExpressionValue` on the `grabbed.isMiddle()` in `mouseReleased` → return
   `original || active` (so `selectAllQuestsInBox` also runs for Shift+left).
4. `@Inject(TAIL)` on `mouseReleased` → `active = false`.

**`QuestScreenMixin`** (target `QuestScreen`):
1. `@ModifyExpressionValue` on the `grabbed.isLeft()` in `drawBackground` → return
   `original && !active` (no panning during a box selection).
2. `@ModifyExpressionValue` on the `grabbed.isMiddle()` in `drawBackground` → return
   `original || active` (draw the box during a box selection).
3. `@Shadow` of the `selectedObjects` field + `@Inject(TAIL)` on `addObjectMenuItems` → adds the
   "Set shape" entry to the **single**-object menu (feature §1.2).

**`QuestButtonMixin`** (target `QuestButton`) — covers the **multi-selection** menu:
- `@Shadow` of `questScreen` + `@ModifyArg(index=0)` on the `INVOKE` of
  `BaseScreen.openContextMenu(List)` in `onClicked` → adds "Set shape (N quests)" to the inline
  menu's list. `selectedObjects` is read through `QuestScreenAccessor` (`@Accessor`).

**`QuestAccessor`** (accessor `@Mixin(Quest.class)`):
- `@Accessor("shape") void queststools$setShape(String)` — writes the private `shape` field, then
  `ShapeMenu` sends `EditObjectMessage.sendToServer(quest)` for each quest.

The shape menu logic lives in `net.gabriele333.fmtt2.queststools.client.ShapeMenu` (an ordinary
class, not a mixin): it can import FTB Quests/Library public types directly. The only non-public
piece of data (`selectedObjects`) is handed over by the mixin that `@Shadow`s it.

**`QuestPanelMixin` (feature §1.3, on top of the §1.1 box selection):**
- `@Shadow` of `questScreen`, `questX`, `questY`.
- `@Inject` on the `INVOKE` of `QuestScreen.openContextMenu(List)` in `mousePressed`, with
  `@Local List<ContextMenuItem> contextMenu` (MixinExtras) → adds the "Add display item" entry,
  delegating to `client.DisplayItemCreator`. The `questX/questY` coordinates are captured while
  the menu is being built.
- `DisplayItemCreator` (an ordinary class) opens the picker and creates the ChapterImage-with-item.

**`ChapterImageButtonMixin`** (target `ChapterImageButton`, feature §1.3):
- `@Inject` on `draw`, `@At(INVOKE, target = Icon.draw(GuiGraphics,IIII)V, shift = AFTER)` (matches
  both occurrences) → `GuiHelper.setupDrawing()` to restore the render state after the item model
  is drawn (see §1.3, "white outline").
- `@Shadow` of `chapterImage` + `@ModifyVariable(STORE, ordinal=0)` on `openEditScreen` (its only
  `String` local) → the properties screen title becomes the item's name.
- `@ModifyArg(index=0)` on the invoke of `ContextMenuItem.setYesNoText(Component)` in `onClicked`
  → delete confirmation with the item's name (see §1.3, "readable name").

**`ChapterImageMixin`** (target `ChapterImage`, features §1.3 and §1.4 — in the mixin json's
**client** list: the class exists server-side too, but `fillConfigGroup` is `@Environment(CLIENT)`
and would be stripped on a dedicated server):
- `@Inject(HEAD, cancellable)` on `getTitle` → if `getImage().getIngredient()` is an `ItemStack`,
  return `stack.getHoverName()` (the context menu title fix, see §1.3); if it is a
  `MultiblockIcon`, return `displayName()` (§1.4).
- `@Inject(TAIL)` on `fillConfigGroup` → re-registers the `image` property as an `ItemStackConfig`
  instead of an `ImageResourceConfig` (the "vanished image" fix, see §1.3); for a `MultiblockIcon`
  that same `image` row becomes the type picker and the size/glass rows are added
  (`MultiblockCreator.addSpecConfigs`, §1.4).

**`IconMixin`** (target `dev.ftb.mods.ftblibrary.icon.Icon`, feature §1.4 — in the mixin json's
**common** list, see §2):
- `@Inject(HEAD, cancellable)` on the `getIcon(Ljava/lang/String;)` overload only (static) → if the
  string starts with `fmtt2-multiblock:`, return `MultiblockIcon.of(string)` (which keeps
  unparseable strings verbatim — never any data loss).

**Supporting client classes (feature §1.4,
`net.gabriele333.fmtt2.queststools.client.multiblock`):** `MultiblockType` (the multi-mod type enum
+ valid-by-construction generators + per-type size clamps, including overrides: square/odd turbine,
fixed fusion and quantum bridge), `MultiblockSpec` (a serialisable record, parsed/normalised once),
`MultiblockIcon` (a common-safe Icon), `MultiblockRenderer` (one-shot FBO render + LRU cache with a
permanent fallback on error), `MultiblockOrbitDrag` (mouse rotation state), `StructureRenderLevel`
(a full-bright fake `BlockAndTintGetter` with culling); plus `client.MultiblockCreator` (the
creation flow + shared config rows).

**Sandboxed BER phase (for the Draconic Reactor and future BER-based blocks).** The draconic
reactor blocks have *dummy* baked models: all the visuals live in their BlockEntityRenderers (.obj
models through CodeChickenLib + DE's custom shaders). The renderer therefore has a **third phase**:
for every `EntityBlock` it creates a fake block entity (`newBlockEntity`), attaches the **real
client level** to it (needed for time/random), lets a per-mod hook configure it and invokes the
real BER through the dispatcher, plus BrandonsCore's `renderTransparent` phase (the
`BlockEntityRendererTransparent` interface, which the vanilla dispatcher never calls — that is
where the reactor's shield lives). Every block is in try/catch: a BER that cannot cope with a fake
BE costs only that block type's visuals (a session-wide `BER_SKIP` cache, logged once), never the
render — e.g. the Mekanism turbine casings (whose BER draws the blades by reading the real
multiblock) are dropped this way, and the turbine stays static. Per-mod hooks (`DraconicBeSetup`,
gated on `ModList.isLoaded`): the core receives fuel/temperature/shield/RUNNING state (the plasma
sphere scales with fuel×temperature: a pristine BE would render a 0.5-block sphere) and the
stabilisers/injectors receive their `facing` towards the core (it lives in the BE, not in the
blockstate). DE + BrandonsCore + CCL are `compileOnly` (a soft dependency, like AE2).

**Animated types (`MultiblockType.animated()`).** Types with time-driven BER visuals (so far only
the Draconic Reactor, 6 blocks) bypass the texture cache and are **re-rendered every frame**
through the preview's live path (which skips the key-change check for them): the fake BEs'
animation fields are re-derived each frame from DE's client tick counter
(`ClientEventHandler.elapsedTicks`), imitating the real tick accumulations (`coreAnimation += 1`,
`animRotation += 15`), with the real partial tick passed to the renderers → a pulsing/spinning
sphere and rotating rings. Cost: tessellating 6 BERs per frame, negligible — the flag should only
be granted to small structures. The stabiliser/injector→core **beams** are replicated by
`DraconicBeSetup.renderExtras`: the core's `renderTransparent` would only draw them for components
resolvable through `level.getBlockEntity` (null for fake BEs), so the same public `renderShaderBeam`
calls are made with the same uniforms, taking the positions from the generated structure.

**Mind the config row ids:** `ChapterImage.fillConfigGroup` already uses the ids
`x, y, width, height, rotation, image, color, alpha, order, hover, click, ...`, and registering the
same id **replaces** the row (that is exactly the mechanism behind the `image` fix!): the structure
size rows therefore use the ids `mb_width`/`mb_height`/`mb_depth`/`mb_glass` — with `width`/`height`
you lost the ability to resize the image on the map.

**The `DRACONIC_REACTOR` type** (rules from `TileReactorCore`, DE 1.21 sources): components are
searched on every axis at distance **4–7** from the core (the first non-air block must be a
component facing the core), with exactly 4 stabilisers around one axis (the classic layout: Y axis,
injector below). Option `stabilizer_distance` (4–7); the box dimensions derive entirely from it in
`clamp` (the extra height above the core keeps the plasma sphere inside the frame).

**Capacitor Bank — the energy bar (home-made).** EnderIO's `CapacitorBankBER` cannot be used on a
fake BE: on the client it reads `getEnergyStorage()`, which returns the synchronised storage (empty,
max 0 → no fill), and it resolves continuity/face-culling through the level (which fake BEs do not
populate). So the bar is drawn by `EnergyBarRenderer`: coloured quads (`RenderType.lightning()`,
POSITION_COLOR, depth-tested, no cull) on the cuboid's 4 outer vertical faces, filled from the
bottom up to the charge fraction — **Phase 4** of the renderer, after the BER phase. Option `charge`
(0/25/50/75/100, default 100). No compile dependency on EnderIO.

**The `ENERGY_CORE` type** (the Draconic "battery", rules from `TileEnergyCore`): an **active** core
hides its block shell (replaced in game by invisible structure blocks) and shows only the **energy
sphere** (scaling with tier through `RenderTileEnergyCore.SCALES[]`, ~1→10) and the **4 stabilisers**
on the horizontal plane. The generator therefore only places `energy_core` (blockstate
`active=true` → INVISIBLE baked model, the BER draws it) and 4 `energy_core_stabilizer`
(`large=true`) at a tier-dependent radius (`stabilizerRing`, so the ring contains the sphere and the
framing fits everything). Options `core_tier` (1–8) and `stab_distance` (auto = just outside the
sphere, or 3–12); the box (`coreBoxHalf`) contains both the sphere and the ring, so nothing gets
clipped. `DraconicBeSetup` sets, on the core: `tier` (through **reflection** on `ManagedByte`'s
`value` field — `tier` carries the `CLIENT_CONTROL` flag and a normal `set()` on the client **sends
to the server and gets reverted**, besides spamming one packet per frame), `active`, and — to make
**glowing spheres and beams** appear — `stabilizersValid=true` + `stabilizerPositions[]` (the
core−stabiliser offsets, as in `findComponents`; the core's `renderStabilizers` BER bails out if
those are not populated, exactly like the reactor's `componentPositions`). The stabiliser BEs
receive `isValidMultiBlock`/`isCoreActive`/`multiBlockAxis`=Y/`coreDirection`/rotation (their BER
draws the **frame**; the spheres and beams are drawn by the core). An **animated type** (sphere,
discs and beams rotate, time-driven). The tier travels with the spec: `configure` receives the
`MultiblockSpec` (chain renderInto → renderBlockEntities → configure) and reads the options.

**View-mode rotation (`viewRotate`, a spec field, serialised as `view=<0|1>`).** The author can tick
"players can rotate" on a multiblock display: then **non-editors** can drag with the left button to
inspect it too. In edit mode the drag **persists** (as before: `EditObjectMessage`); in view mode it
does **not** — the angle is kept client-side only, in `MultiblockOrbitDrag.heldView` (a
`WeakHashMap` keyed by image, cleared when the chapter reloads). Render routing:
`ChapterImageButtonMixin.draw` (HEAD) sets the `renderContext` (the image about to be drawn) and
clears it after the icon; `MultiblockIcon.draw` asks `MultiblockOrbitDrag.currentView()` for the
angles — live while dragging, otherwise held, otherwise null (use the angles saved in the spec). The
preview's shared FBO target is safe with several images in the same frame because each one blits
immediately after its own render.

**Ender IO note:** it has no multiblocks other than the capacitor banks.

Every target method has **exactly one** occurrence of `isLeft()`/`isMiddle()`/`PUTFIELD grabbed` in
the region of interest, so `ordinal = 0` is sufficient and unambiguous (verified with `javap -c` on
the 2101.1.27 jar).

---

## 4. Build, dependencies and naming

- Reference loader/target: **NeoForge, MC 1.21.1**.
- Main dependency: `dev.ftb.mods:ftb-quests-neoforge` (repo `https://maven.ftb.dev/releases`).
  Being published with Gradle module metadata, it transitively pulls **Architectury**, **FTB
  Library** and **FTB Teams**, which end up on the runtime classpath too → `runClient` starts with
  the whole FTB Quests stack loaded as mods.
- In FMTT2 the dependency is declared in `build.gradle` (`implementation
  "dev.ftb.mods:ftb-quests-neoforge:${ftb_quests_version}"`, with the versions in
  `gradle.properties`) and in the toml (`modId="ftbquests"`, `type="required"`,
  `ordering="AFTER"`). The mixin config `fmtt2-queststools.mixins.json` is registered in the toml
  with a `[[mixins]]` block.
- Every version is parameterised in `gradle.properties` (no hard-coded numbers in the code or in
  the toml: the toml uses placeholders expanded by `generateModMetadata`).

---

## 5. Version matrix (update on every port)

| MC       | FTB Quests (line)  | `MouseButton` package                         | Architectury | Notes |
|----------|--------------------|-----------------------------------------------|--------------|-------|
| 1.21.1   | 2101.x (2101.1.27) | `dev.ftb.mods.ftblibrary.ui.input`            | 13.0.x       | current reference line |
| (recent) | `main` branch      | `dev.ftb.mods.ftblibrary.client.gui.input`    | ≥14          | GUI packages renamed; check `drawBackground`/`mouseReleased` |

To add a row: download the target version's jar, run `javap -p -c` on `QuestScreen` and
`QuestPanel`, confirm that the patterns from §2/§3 still exist and note any renames here.

---

## 6. Porting checklist for a new version

1. Update in `gradle.properties`: `minecraft_version`, `minecraft_version_range`, `neo_version`,
   `ftb_quests_version` (+ `ftb_quests_version_range`).
2. `javap` the new FTB Quests jar → check/update:
   - the packages of `MouseButton` and `ContextMenuItem` (both the Java **imports** and the `L…;`
     descriptors in the `@At`s); on recent versions they are in `client.gui.input` / `client.gui.*`;
   - the signature/existence of `drawBackground`, `mouseReleased`, `mousePressed`,
     `selectAllQuestsInBox`, and the `grabbed` field;
   - that there is still **exactly one** occurrence of `isLeft()`/`isMiddle()` per method (otherwise
     adjust the `ordinal`s);
   - shape feature: the signature of `addObjectMenuItems`, the `selectedObjects` field, `Quest`'s
     `shape` field, `QuestShape.idMapWithDefault`, `EditObjectMessage.sendToServer`;
   - display item feature: the invoke of `QuestScreen.openContextMenu(List)` in
     `QuestPanel.mousePressed`, the `questX/questY/questScreen` fields, `ChapterImage` +
     `Chapter.addImage`, `ItemStackConfig`/`SelectItemStackScreen`, `ItemIcon.getItemIcon`;
   - display item fixes: `ChapterImage.getTitle`/`fillConfigGroup`, `Icon.getIngredient` (delegated
     by `LazyIcon`), `ChapterImageButton.openEditScreen` (the LVT with its `name` local, check with
     `javap -l`) and the invoke of `ContextMenuItem.setYesNoText` in `onClicked`, and that the
     `"image"` id and `ImageResourceConfig` are still used in `fillConfigGroup`;
   - multiblock display feature: the signature of `Icon.getIcon(String)` (and that the splits on
     `" + "`/`";"` still happen after HEAD), `TextureTarget`/`RenderSystem` (projection
     backup/restore, `getModelViewStack`), the NeoForge overload of
     `ModelBlockRenderer.tesselateBlock(..., ModelData, RenderType)`,
     `ConfigGroup.addEnum/addInt/addBool` + `NameMap.Builder`, `EditConfigScreen`; if the Mekanism
     version changes, re-check the rules encoded in `MekanismMultiblock` (§1.4) and the blocks'
     registry names against its validators.
3. Update the matrix in §5.
4. `./gradlew build` (compile) and `./gradlew runClient` (boot + open the quest editor for the
   manual test of §1.1).

---

## 7. Towards multi-loader (an architectural note)

The project as it stands is single-target (NeoForge 1.21.1). The feature, however, is **purely
client GUI** and all the useful logic sits in ~4 tiny injectors plus a boolean flag: the cost of
duplicating it across versions/loaders is therefore low.

If several versions/loaders ever need to live in one repo, the natural evolution is an
**Architectury** setup (`common` + `neoforge`/`fabric` modules), keeping the mixins in the common
module and isolating the version-specific values (the `MouseButton` package, and so on). This file
stays valid as the specification shared by all the modules.
