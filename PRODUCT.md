# Product

<!-- impeccable:product-schema 1 -->

## Platform

ios

## Users
One player: Kyle, playing on his own iPhone. Sessions are often long, but the phone is held vertically and played one-handed like Polytopia.

## Product Purpose
A personal fork of Unciv4iOS (the iOS port of Unciv, the open-source Civ 5 remake). It keeps Civ 5's full strategic depth and makes it play like a vertical, one-handed phone game with a Polytopia-style flat low-poly look. It is not for sale and has no store, growth, or monetization goals. Success means Kyle keeps choosing it over Polytopia because it doesn't get repetitive.

## Positioning
Polytopia's one-handed vertical play with Civ 5's depth. Polytopia feels linear after a few playthroughs, and Civ on a phone feels cramped and built for landscape. This fork aims to fix both.

## Operating Context
- Portrait orientation first. Landscape may keep working but is not the design target.
- One-handed thumb reach is critical: primary actions sit in the lower part of the screen.
- Long sessions: the UI must stay comfortable over hundreds of turns, not just a quick demo.
- Built on Unciv (Kotlin, libGDX, Scene2D UI) through the Unciv4iOS RoboVM port. Builds and uploads to TestFlight run on Kyle's Mac.

## Capabilities and Constraints
- Rules: the full Civ 5 ruleset as Unciv ships it (religion, policies, great people, diplomacy, city-states, trade, and so on). Nothing is cut. Mechanics may be trimmed or added later.
- Map: hex grid (Unciv's rules depend on hexes). The Polytopia feel comes through art and layout, not a square grid.
- Map size: chosen per game, keeping Unciv's full range of options.
- Performance matters more than visual richness. Flat low-poly art is chosen partly because it is cheap to render.
- Unciv already has partial portrait support (`isPortrait` and `isCrampedPortrait` in BaseScreen). The iOS Info.plist already allows portrait.
- License: code is MPL 2.0. Media files use mixed CC licenses. Replacement art must be original or compatibly licensed.

## Brand Commitments
- Visual reference Kyle made binding: The Battle of Polytopia's styling (flat, low-poly, bright, readable).
- The current Unciv/Unciv4iOS styling is explicitly disliked and is not a reference.

## Evidence on Hand
- The upstream code and assets are in this repo. There are no custom assets yet.

## Product Principles
1. Depth without clutter: all of Civ 5 is reachable, but only what matters right now is on screen.
2. Thumb first: anything done every turn is reachable one-handed in portrait.
3. Map is the hero: the UI gives the map as much of the screen as it can.
4. Cheap to render: pick art and effects that keep the phone cool over long sessions.
