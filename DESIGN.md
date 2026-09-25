# Design

Visual rules for the portrait Unciv fork. Product truth lives in PRODUCT.md; the working mock is `mocks/portrait-hud.html`.

## World
Polytopia's grammar: flat, crisp, toy-like low-poly. Bold color blocks read first, detail second. Light always comes from the upper left.

## Map
- **Terrain (direction A, chosen 2026-09-25):** thin hex slabs, one clean color per terrain in two tones (facets facing the light are lighter), a faint seam between tiles, 4px slab sides in a darker shade of the same color. Water sits slightly lower. Rejected: thick layered "diorama" slabs and soft blended tiles.
- **Shorelines:** every land edge touching water gets a sand band; every water edge touching land gets a pale shallow band and a white surf line.
- **Palette:** ocean `#2f7ad6`, coast `#3fb5e3`, grassland `#88cd50`, plains `#e3ca52`, desert `#eba45b`, tundra `#bdb993`, snow `#f3f7fa`, mountain base `#a3acb6`, beach `#f6e3b0`.
- **Hex shapes are always code-drawn** so they tile exactly. Everything standing on a tile is a sprite.
- **Sprites on tiles:** terrain features (with 2 to 3 variants per common feature, picked per tile), resources, improvements, natural wonders, wonder landmarks, units. All share one style: simple chunky low-poly, two tones per color, no faces or fine ornament, no outlines, reads at 24px.
- **Cities (chosen 2026-09-25):** one sprite per era; the look changes with the owner's era (mud brick, stone, brick and smokestacks, towers), not with population. Roofs and flags are key green and take the owner's color. A wonder stands at the front right of its city tile. Population lives on the city banner.
- **Zoom (chosen 2026-09-25):** default is about 5 hexes across the screen (Polytopia scale; Civ 6 on iPhone was criticized for units too small to tap). Drag pans, pinch zooms from 0.5x to 2.2x. Past 0.8x the map turns strategic by itself, replacing Civ's separate Strategic View button: decorations and sprites hide, units become civ-colored chips with their silhouette, resources become pins, borders thicken. City banners and labels hold one screen size at every zoom.
- **Fog:** unexplored tiles are covered by white clouds.
- **Borders:** civ-colored line inset on the owner's edge, 12% civ tint on owned tiles.

## Units
- Generated sprites in the approved Bowman style, facing right in three-quarter view.
- Team-colored cloth is generated in key green and recolored per civ (Unciv's `-1` color layer in the real game). One sprite serves every civ.
- Motion is whole-sprite, Polytopia style: idle breathing, crouch-hop-squash moves with dust, lunge strikes with an attack pose, hit flash with knockback and a popping damage number.

## Big moments
- **Leader portraits** and **wonder completion scenes** are high-detail flat pictures. Detail is allowed here because they never animate or recolor.
- Wonder completion: the scene zooms in from a warm flash, sparkles twinkle, then a card rises in thumb reach with the effect, the quote, and Continue.

## Identity (chosen 2026-09-25)
- **App icon:** the floating hex island (grass tile with a tiny blue-roofed city, a snowy mountain and pines, earth underside) on bright sky blue. Source: `mocks/art/appicon-1024.png`.
- **Title and main menu:** the army lineup art (units from every era on a hex strip under a big sky), the wordmark on top, a big yellow Continue with the current game, then New game, Load game, Civilopedia, Settings.

## Screens (chosen 2026-09-25)
Mocks live in `mocks/`; the chosen direction is the one to build.
- **Social policies:** B, one branch at a time as a vertical tree like the tech tree, branch rail at the bottom (`policies-religion.html`).
- **Religion:** A's path (pantheon, prophet, found, enhance) as the main sheet, with B's map lens one tap away: follower rings on cities and spread lines (`policies-religion.html`, direction D).
- **New game:** A, swipe through leaders on a big card, every setting is one chip with Unciv defaults, Start under the thumb (`setup-cs-victory.html`).
- **City-state:** B, the race to Ally: each quest, gift or pledge shows where it moves you against rivals (`setup-cs-victory.html`).
- **Victory:** A, four victory tracks with rivals as dots; the end is a portrait moment like a wonder (`setup-cs-victory.html`).
- **Trade:** A, two stacked piles (you give on top, they give below) with one tray in thumb reach (`trade-notes-pedia.html`).
- **Notifications:** A, a turn digest separate from the Next decision queue; tapping one flies the map there (`trade-notes-pedia.html`).
- **Civilopedia:** A, your game first, ten shelves, search at the bottom, full-screen entries with tappable links (`trade-notes-pedia.html`).
- **Tech:** Next up (current research, available picks, goals) plus the full tree in four lanes (`portrait-hud.html`).

## Interface
- Portrait, one-handed. Everything used every turn lives in the bottom third; rare actions (menu) go at the top.
- Navy translucent panels `rgba(16,31,47,.9)`, white round action discs, yellow `#ffc93c` for the primary action.
- Bold rounded type (SF Pro Rounded on iPhone). Touch targets at least 48px, primary buttons 56px or more.
- Tech tree icons stay Unciv's originals. Generated art is reserved for the map, units, leaders, and wonders.

## Asset pipeline
- `mocks/art/gen.py` generates art through the CLIProxy image tool on a magenta backdrop and keys it out with ImageMagick.
- `mocks/art/inventory.py` lists every map-facing item from the ruleset with its method and status (`INVENTORY.md`, `inventory.json`).
