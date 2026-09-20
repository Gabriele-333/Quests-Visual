Quests Visual adds new visual display tools for FTB Quests, allowing modpack creators to build richer and clearer quest pages.

With this add-on, you can place real item models, 3D multiblock previews, customizable player mannequins, live 3D entities, world structures and dimension portals directly inside FTB Quests chapters. These displays can be used to show machines, structures, equipment setups, mobs, progression goals, or decorative elements without relying only on flat images.

![](https://github.com/user-attachments/assets/15ad46ee-f343-4484-b6ff-87a5427eaf41)

 

The mod also includes useful editing improvements for quest authors: applying a shape to every selected quest in one go, and rotating or flipping a whole selection around a pivot of your choosing.

**Main Features**

*   Display items and blocks using their real in-game models
*   Add configurable 3D multiblock previews
*   Add player mannequins with custom equipment
*   Add any registered entity as a live, animated 3D display
*   Add any structure shipped by your modpack, from a single piece to a whole assembled structure
*   Add portals, frame included, from vanilla and from mods
*   Rotate supported displays inside the quest page (hold left click and drag)
*   Apply quest shapes to multiple quests at once
*   Fast rotate and flip quests

![](https://github.com/user-attachments/assets/6557d5a7-981f-406b-9345-1ff099709681)

**Display Entities**

- Show any entity registered in your instance (vanilla or modded)  
  - Always rendered in 3D and rotatable, just like the multiblock displays  
  - Plays the entity's animation (e.g. a flying Ender Dragon, flapping wings, idle motion)  
  - Choose the animation speed (paused, 0.5×, 1.0× real-time, 2.0×, and more)

**Display Structures**

*   Browse the structures shipped by your modpack, grouped by mod, and drop them into a quest page
*   Pick a single template piece, or the whole structure
*   Chests, signs and banners in the template are rendered with their real contents
*   Worldgen markers (structure and jigsaw blocks, barriers, light blocks) are hidden by default, with an option to show them
*   The result is the shape of the structure, not a copy of one generated instance: no terrain fitting, no loot rolls, no fluids

A whole structure is put together from whichever source gives the most faithful picture:

*   Jigsaw pools, solved on the spot: villages, ancient cities, trial chambers, bastions, pillager outposts, trail ruins, and any modded structure built with jigsaw blocks
*   Pre-generated instances, shipped with the add-on, for the dungeons a mod builds entirely in its own code and that nothing can assemble from data alone
*   Built-in ports of a mod's own generator, for the shapes that are carved out of the world rather than built from pieces
*   Your own part lists, dropped in `config/questsvisual/composites`, if you would rather arrange a structure by hand. They win over everything above, so a pack can always override a result it does not like

**Supported Structure Displays**

Everything below is optional and nothing is a hard dependency. Blocks and templates are looked up by name, so a mod you do not have simply disappears from the picker.

*   Vanilla and any jigsaw-based mod — works out of the box, no integration needed
*   The Twilight Forest — 18 of its 21 structures, including the Dark Tower, Aurora Palace, Knight Stronghold, Labyrinth, Mushroom Tower, Lich Tower, Naga Courtyard, Hedge Maze and the hollow hills
*   The Aether — the Bronze, Silver and Gold dungeons, and the Large Aercloud
*   The Gaia Dimension — the Malachite Watchtower and the mini towers, plus every single piece they are built from

The catalogue is read from the mod jars themselves, which are identical on a client and on the server it is connected to. Everyone with the same mods sees the same structure, in single player and online alike.

**Display Portals**

*   The Nether portal, the End portal, the End gateway, and the portals added by your mods, all discovered automatically
*   Known portals come with the right frame already set: the Aether's glowstone, the Gaia Dimension's keystone, the Twilight Forest's pool in a ring of flowered grass
*   Frame block, shape and size are part of the display, so you can build a Nether portal out of quartz or an End portal out of deepslate
*   Three shapes: upright arch (Nether family), flat ring around a pool (End family, Twilight Forest), and the End gateway cage

  

![](https://github.com/user-attachments/assets/80c29bc6-d899-44e3-a930-71608119fcab)

**Supported Multiblock Displays**

Optional integrations are available for:

- Mekanism / Mekanism Generators  
  - Applied Energistics 2  
  - Ender IO  
  - Draconic Evolution  
  - Ars Nouveau  
  - PneumaticCraft: Repressurized  
  - Mystical Agriculture
 

**Requirements**

FTB Quests 2101.1.28 or newer (it brings FTB Library, FTB Teams and Architectury along with it). Tested against the latest release, 2101.1.36.
