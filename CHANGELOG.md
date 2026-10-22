# Changelog

## v1.9.2

### Added

- The steamboat's paddle wheels now splash: each paddle hitting the water plays a water sound, faster when the wheels turn faster.
- The steamboat and the airship engine now run with a steam engine sound whenever the fuel tank has fuel, docked or underway.
- You can forget a camp respawn point from the world map: open the menu of its house icon and choose "Forget this respawn point". It works even after the camp is packed.

### Fixed

- Sleeping in a camp no longer replaces your bed's respawn point, and claiming a bed no longer replaces your camp respawn point, even when they are close to each other. You come back to whichever is nearest to where you fell.

### Changed

- The mod's technical blocks (vehicle parts, placed packs, station decorations) no longer show in the creative library or search. They can still be obtained with `/give`.
- Crafted items now show a quality matching the Explorer's workbench tier they need: uncommon (green) for tier 1, rare (blue) for tier 2, legendary (gold) for tier 3. The large tent is the exception and stays rare, and the Explorer's workbench itself is common, like the game's other workbenches.
- The pilot contract now costs 50 gold bars, 35 silver bars and 15 concentrated life essences (was 30, 20 and 10).
- The pack left on the ground at a camp is now half its size, and the large tent's pack sits in front of the tent wall, next to the entrance.

## v1.9.1

Explorers get their own workbench: every camp pack and vehicle crate is now crafted there, and its look grows with each tier.

### Added

- The Explorer's workbench: crafted at the basic workbench, in the Crafting tab with the other benches, upgraded twice, with a Camp tab and a Transport tab. Plans and little models of what you can build appear on it at each tier, and a balloon and an airship float above it at the top tier.

### Changed

- All the mod's recipes (camp packs, balloons, airship, steamboat and balloon station crates) are now crafted at the Explorer's workbench, at the same tiers as before.
- The animal transport balloon crate now needs 35 iron bars, 15 thorium bars and 4 concentrated life essences instead of iron, cobalt, thorium and fire essence.

### Fixed

- The steamboat crate's picture now shows the paddle wheel under the cabin, on the hull.

## v1.9

A paddle steamer joins the fleet: set it down on a lake, a river or the sea, fuel its tank and cast off.

### Added

- The steamboat: a paddle steamer set down on water from its crate. It sails on the surface, backs up slowly, its paddle wheels turn, and it carries 12 passengers. Nobody can jump off with a glider from it for now.
- Light smoke rises from the airship's rear burner outlets and the steamboat's chimneys while they are parked with fuel in the tank. The steamboat's smoke thickens when it sails.

### Changed

- The blocks shared by the airship and the steamboat (helm, engine, fuel tank, stool, chair, inkwell, rope ladder) now have general names and descriptions.
- The airship's helm is now an ancient stone statue holding a golden wheel, facing the bow. It is solid, and you still use the wheel to take off and land.

## v1.8.5

### Fixed

- The camp pack no longer disappears from your own back after you change worlds, for example when you go into or come back from a goblin breach.

## v1.8.4

### Added

- Anonymous telemetry through Beacon: number of servers and players, mod and game versions, hourly counters of the features used, and the mod's own caught errors without their message. Names, positions, worlds and chest contents are never sent. The server owner can switch it off with Beacon's consent window, the `/beacon` commands or its settings file. See the wiki.

### Changed

- The section titles of the airship's destination list now look like the ones in the station master's window.

### Fixed

- Other players now see the camp pack on your back, also when they come into view or join after you put it on.

## v1.8.3

Tent crates become camping packs: a leather backpack that you carry and that shows on your back. A new Sleeping bag starts the chain, and each upgrade keeps what was inside.

### Added

- Three camp packs: Sleeping bag (new), Small tent and Large tent. Right-click the ground to set the camp up, use the pack on the ground to pack it away. The camp's contents travel in the pack.
- The sleeping bag sets up a straw bed, a lit brazier and its pack on the ground. It packs itself away when you wake up after a full night.
- Upgrade a pack at the workbench (Sleeping bag to Small tent to Large tent) and keep its contents. Keep the lower pack on you. A craft that would lose contents is refused.
- The camp where you last slept becomes your camp respawn point in this world, even after you pack it. If the spot is blocked, you are moved to a free spot within 8 blocks. Beds of the game are not touched.
- The pack with the highest level in your inventory shows on your back. `/orbishorizon camping back on|off` (operators) switches it. It stays off if the True Backpack mod is installed.
- `/orbishorizon camping pack`: packs your nearest tent within 8 blocks, for tents set up before this version.
- A camping pack cannot be stored in a tent chest, full or empty, so packs cannot be nested. The old tent crates are refused too. If one gets in anyway, it is dropped on the ground when you pack the tent.

### Changed

- Tent crates are replaced by camp packs. Full crates in your inventory are converted into packs of the same level, with their contents, when you join a world. Empty crates disappear. Crates in chests or on the ground still work, and are renamed "(old crate)" so they are not mistaken for the packs. Tents already set up get a pack on the ground at their entrance, unless a block is in the way.
- The recipes of the old crates are gone. Packs are crafted at the basic workbench, tier 1, 2 and 3.
- Credit: the back view of the pack is inspired by True Backpack (CC0).

## v1.8.2

The airship gets rope ladders: lower one to the ground whenever it is parked in the air, so nobody is ever stuck below it.

### Added

- The airship now has a rope ladder on each side. Two levers, one beside each gate, lower and raise it one rung at a time while the airship is parked in the air. The lever position shows whether the ladder is down. Pull the lever again while it moves to reverse it. Anyone caught by the rope as it rises is set down on the ground without harm.
- Before any take-off (helm, pilot trip, captain's chart, recall departure) the ladder is raised first, then the airship leaves. Asking for a second departure while the ladder is rising is refused.
- If the captain jumps off with a glider or dies during a trip with the pilot, the airship now sinks until the bottom of the ladder on the captain's side is at most 30 blocks above the ground, sets down in blocks and lowers that one ladder. The pilot tells the passengers still on board.
- The deck gate above a ladder opens when the ladder is fully down and closes when it is fully up, but only if the airship opened it itself. A gate you opened yourself stays as you left it.

### Changed

- When you call your airship to a station, the same rope ladder comes down to the boarding platform and the minute starts once it touches the bottom. Anyone still on the ladder when the airship leaves is set on the ground instead of being lifted onto the deck.
- An airship set up before this version keeps its wooden rungs and has no levers. It gets the rope ladders and levers the next time it lands.

### Fixed

- Leaving the game from the captain's chair during a trip no longer drops you on the ground under the airship. Once the airship is set down, you come back on its deck next to the chair.

## v1.8.1

A small release that closes a shortcut: leaving the game during a flight no longer takes you to your destination.

### Changed

- The inside of the airship envelope is now empty: the braziers and the cobwebs that were hidden in it are gone. An airship set up before this version still has them and would leave them floating in the air at take-off. Have an operator remove it, then set up a new one.

### Fixed

- Leaving the game during a flight no longer gets you to your destination. On a public transit trip you go back to the departure station. On a balloon or airship flown by someone else you go back to where you boarded.
- The captain's map now looks like the game's handheld map, in your hand and in your inventory. The game refused its old texture.

## v1.8

This release brings your own pilot. Hire one, let them fly you to your markers or to a station, call your airship to a station when you need it, and jump off with a glider. Tents now keep their contents in the crate.

### Added

- The small tent now has a small chest outside, on the right side as you face the entrance.
- Packing a tent keeps the contents of its chests and campfire inside the full crate instead of dropping them on the ground. Set the crate up again, anywhere, even after storing it, dropping it or handing it to another player, and everything goes back into the same chest or campfire, in the same slot. A crate that you craft starts with an empty tent.
- You can call your airship to a balloon station. Talk to the stationmaster and pick Recall my airship (you need an airship with a pilot, parked in the same world). The ship comes in without using fuel and settles above the station with a ladder down to the boarding platform. You have a minute to climb aboard, and anyone still on the ladder at take-off is lifted onto the deck. Give the pilot an order or take the helm to leave early. At the end the pilot asks where to go, or moves the ship about 70 blocks away so the quay is free. A server restart or a removed station never leaves the airship above the station.
- The workbenches and furnaces on the airship keep what is inside them during the flight, like the chests and the fuel tank. Take-off is no longer refused when a workbench is not empty.
- You can hire a pilot for your airship. Buy a pilot contract from a stationmaster (30 gold bars, 20 silver bars and 10 concentrated life essences) and use it on the helm of your landed airship. Talk to the pilot: "I take the helm" sends them to sit on the stool so you can fly, "Take the helm back" sends them back to the helm. They walk through the cabin door between the two. The helm refuses to take off while the pilot holds it or has not sat down yet. The pilot's stool is no passenger seat (14 passengers instead of 15). In flight the pilot appears as a seated mannequin on the stool and sits back down when you land. The pilot is found again after a restart and goes away with the airship when an admin removes it.
- Your pilot can now fly you somewhere. Talk to the pilot and pick Take us to..., then one of your map markers (including the ones shared with the whole world) or a balloon station that is at least 32 blocks away. You sit in the captain's chair, the pilot flies the airship alone (climbing over obstacles, burning fuel as when you fly) and sets it down in the air above the destination, or 70 blocks from a station's pad. You get a captain's map for the trip: select it and, after a 3, 2, 1 countdown, the pilot stops and lands where the airship is. Change item to cancel. The map disappears at the end of every trip. The pilot also stops if the fuel runs out, if the way is blocked, or if you die, leave the game or change game mode.
- Balloon stations now keep a protected area around their landing pad (32 blocks around it, up to 40 blocks above it). You cannot set up a balloon, an airship or a tent crate close to a station, and you cannot choose to land a balloon or the airship there. The message names the station. If something forces a landing there (the fuel runs out, the pilot dies, you leave the game), the vehicle is still set down safely.
- You can only own one airship at a time. A second airship crate is refused while you already have one, on the ground or in flight, in any world. Crafting several crates is still allowed.
- The chart on the desk of the airship cabin is now a captain's chart. Use it to pick a destination without talking to the pilot. If the pilot is at the helm, the airship leaves right away. If the pilot is sitting on the stool, they walk to the helm first, then you are off. You need a pilot, and only the captain can use it.
- Passengers of balloons and of the airship can now jump with a glider. Take a glider in hand while you fly as a passenger, wait a moment and a countdown appears. At the end you drop out under the vehicle, glider in hand, and you click to open it. Switch items during the countdown to cancel. The pilot cannot jump.

### Changed

- The smoke from the airship burners now rises at the tip of the flames and is thinner and paler, so it no longer fills the screen.
- The outside view of the airship is steadier. It no longer re-centres while the ship turns, only when it moves in a new direction, and the camera sits further back.
- The protected area around a station is smaller, 32 blocks around the landing pad and 40 blocks above it.

### Fixed

- Your pilot no longer gets stuck against the counter in the cabin when walking between the helm and the stool. If they ever do, they put themselves back at their post, or you can type `/orbishorizon airship pilot reset`.
- Calling an airship that was parked far from any player no longer loses what was inside its chests, furnaces and fuel tank, nor the level of its upgraded workbenches. If something cannot be read, the airship (or the tent being packed) stays put and you are asked to come closer.

## v1.7

### Added

- Your hot air balloons, airships and tents now appear on your world map, each with its own icon and the name of its model (for example "Small hot air balloon" or "Large tent"). The icon follows the vehicle in flight. Other players do not see them unless you choose Share in the menu of the icon (Stop sharing hides them again). Once shared, other players see "<model> of <your name>". The player who sets up the crate owns the vehicle. A balloon set up before this version gets its owner the next time it lands.
- Balloon stations now appear on the in-game world map, with a balloon icon and their name. Stations in parts of the map you have not explored stay hidden by the fog. Select one on the map to follow it with the compass.
- Passengers of balloons and of the airship are now safe while they travel. They take no damage and monsters no longer notice them. The pilot stays exposed. Operators can change this with `/orbishorizon passengers protect full|damage|off`.
- If the pilot of a balloon or of the airship dies, the vehicle is set down where it is and the passengers are freed with a message, so that one of them can take the pilot's place. If there is no room to set it down, it still comes down by itself as before.

### Changed

- Passengers of balloons (large, animal transport and public transport) now see the balloon from the outside, from behind and a little above, instead of from their seat. They can see themselves seated. Airship passengers (and the captain in the chair during a voyage) get the same outside view, from behind the ship.
- `/orbishorizon map share` and `unshare` can be used by any player for their own vehicles. All the other `/orbishorizon` commands stay reserved to operators.

### Fixed

- Getting up from the bed of the small tent, or waking up, puts you in front of the entrance again instead of leaving you stuck in the canvas.

## v1.6

This release moves Orbis Horizon to Hytale 0.7. It needs Hytale 0.7 and no longer loads in Hytale 0.6.8. If you stay on 0.6.8, keep v1.5.

### Changed: animal transport balloon

- The cage lever no longer moves the cage while its gate is open, neither down nor up. Close the gate first.
- If part of the cage is in an area of the world that is not loaded yet, the lever now asks you to come closer instead of saying the cage is damaged.

### Fixed

- Station masters no longer pile up. With Hytale 0.7, every restart of the game left one more station master standing inside the real one. The extra ones are removed the next time you come near the station.
- Animals caught in the cage of the animal transport balloon are no longer doubled when the game starts again far from the balloon.

### Breaking changes from v1.5

- Hytale 0.7 is required. Version 1.5 is the last one for Hytale 0.6.8.

## v1.5

This release adds balloon stations, a way to travel between places without piloting anything. Along the way, the way passengers see a flight changed, crates became the only thing you craft for a vehicle, and a few rough edges of the balloons and the airship were smoothed.

### New: balloon stations

A station crate sets up a station: stairs climbing to a raised stone walkway, a landing pad at its end next to the boarding steps, and a hut with the station master. Every station in the world is a destination for every player. Talk to the station master, pick a destination, and a balloon with no pilot comes down onto the pad. It waits thirty seconds, flies the players sitting in its basket to the station you chose, sets them on the boarding platform at the top of the stairs and flies off. There is no fuel to bring and nothing to steer.

- A station needs about 25 x 11 x 17 blocks and must stand at least 64 blocks from another station. The stone path replaces the ground, and grass and flowers are cleared. A real bump in the terrain or a tree in the way refuses the placement, and the message names the block in the way and its position.
- After placing a station you name it in a window (3 to 24 characters, each name used once). If you close the window, the station is called "Station N".
- The station master looks like a player, with his own look for each station and a blue cap. He stands in his hut and always faces the foot of the stairs. His window shows the state of the pad, the planned departures, and the destinations sorted by name, each with its direction (eight compass points), its distance in blocks and an estimated flight time.
- Only one player at a time can talk to the station master. Departures queue up, five at most, and players within 20 blocks are told about each one.
- The balloon has 12 stools. On arrival, passengers get a welcome message and the name of the station in big letters. The balloon waits five seconds on the pad, then climbs away and disappears. Two balloons on the same pad are kept ten seconds apart so they never cross. If the pad is busy when a balloon arrives, it waits nearby.
- During a trip you see the world from your seat, looking towards the destination, a little to the outside of your seat. If you were already seated when the balloon left, you keep the direction you were looking at. The view is smooth, you cannot look around, and the normal view comes back when you arrive.
- Balloons fly high in a straight line and climb over obstacles. If a balloon is stuck for too long, its passengers are set at the destination. If the destination is removed during a trip, the balloon goes to the station it left or to the nearest one.
- If the server stops or a passenger leaves during a trip, nobody is left in the sky. Players are set on the boarding platform of the destination.
- The braziers of the station do not burn and cannot be put out. The map in the station no longer gets in the way of the station master.
- Stations cannot be placed under water. Removing a station gives back the crate and fills the holes left in the ground with the surrounding block.
- The crate is crafted from the raw materials of the station: a lot of stone (any kind), logs, a little wool, iron and charcoal. Its recipe is turned off by default and an operator turns it on for the whole server.

### New: preview of where the crate will put its structure

While you hold a crate of the mod (tents, balloons, airship, station), you see the structure block by block exactly where it will appear, facing the way it will face, and it follows your aim. Only you see it. It disappears when you put the crate away or place the structure.

### Changed: crafting

- The burner, the large balloon burner, the chain, the airship engine, the airship fuel tank and the burner outlets are no longer crafted. Their materials are now part of the crate recipes, so each vehicle is a single recipe. A recipe never needs more than 40 stacks in your inventory.
- Parts you already crafted stay in your inventory but have no use (the large balloon burner and the burner outlet in particular).

### Changed: passengers

- Passengers of the balloons with seats, the animal transport balloon and the airship, and the seated pilot of an automatic descent, now see the world from their seat: first person, smooth, in a fixed horizontal direction. It is the direction they were looking at if they were already seated at take-off, otherwise towards the inside of the vehicle. They cannot look around while they fly. In the airship, the view does not turn with the ship.
- Passengers are shown to the others as seated mannequins with their own skin, and their real body is hidden. Pilots are shown to the others as a standing mannequin, at the helm in the airship and in the basket in the balloons, instead of flying in the creative pose.
- The previous behaviour is still available to operators with `/orbishorizon balloon passengers follow` and `/orbishorizon airship tune passengers follow`.

### Changed: vehicles

- No crate can be deployed under water any more.
- In flight, the ladders are fully folded and nothing sticks out of the basket.
- The large balloon has a candle on one side of the basket. It lights up in flight too.
- When the animal transport balloon is set down in the air with its cage lowered, it now burns fuel. It did not before.
- When the animal transport balloon runs out of fuel with its cage resting on the ground, it now comes down onto its cage instead of pulling the cage back up first. The cage and the animals stay where they are. Landing on the spot is refused during that descent.
- The large balloon and the airship fly more smoothly. The parts of their model used to be mounted on each other, and each one now rides the pilot directly.
- The airship should no longer burst into breaking particles at take-off. The cause was found (blocks spanning several cells) but this has not been confirmed in game.

### For operators

- `/orbishorizon station list` shows the stations, their position and their queue. `debug` shows the state of the nearest station. `despawn` removes the nearest station within 48 blocks and gives you its crate. `tp <name>` takes you to a station (write spaces as `_`, names are suggested while you type). `recipe on|off|show` turns the crate recipe on or off for the whole server.
- `/orbishorizon balloon render pilot:on|off` and the same option on `/orbishorizon airship render` control the standing mannequin shown for the pilot.
- Stations are saved in `stations.json` in the plugin data folder.

### Breaking changes from v1.4.1

- The separate parts (burners, chain, airship engine, fuel tank, burner outlets) can no longer be crafted. Parts already in inventories are unused.
- Passengers now see the world from their seat by default.
- Stations placed with a test build of this version must be removed and placed again.

### Known limits

- Passengers cannot look around during a flight. In the airship, their view stays fixed in the world when the ship turns.

## v1.4.1

This is a multiplayer fix. A player who joined someone else's game could see only part of the airship, the large balloon and the animal transport balloon in flight. The envelope, the workbenches and the cage bars were there, but the wooden hull, the ladders and the chains were missing, and you could see right through them. The host saw everything, and the small balloon looked fine to everyone.

### Fixes

- Every player now sees the flying vehicles in full, wood, ladders and chains included.
- What was going on: in flight, each vehicle is drawn as a single 3D model built from many small pieces. Some graphics cards only draw about the first thousand pieces of a model and silently skip the rest. The small balloon stays under that limit. The bigger vehicles go well past it, and the pieces at the end of the list, mostly the wood, were skipped on those cards. The host's computer allows more pieces, which is why the host never saw the problem.
- How it was fixed: each flying model is now split into parts of at most 1,000 pieces. The large balloon and the animal transport balloon have two parts, the airship has three. Each extra part is shown by its own invisible carrier that follows the vehicle and turns with it, so no single model goes over the limit any more. Flames, smoke and the glowing engine stay on the first part and behave as before.
- The airship flight texture now has the size the game expects (a multiple of 32 pixels), which removes a warning from the game log on every player's machine.

### For operators

- `/orbishorizon balloon render parts:on|off|noyaw`, and the same option on `/orbishorizon airship render`, control the extra parts from the next take-off. `on` is the default. `off` goes back to the old single model. `noyaw` keeps the parts but stops copying the vehicle's rotation onto them.
- `/orbishorizon balloon debug` and `/orbishorizon airship debug` now show the number of parts.

### Known limits

- A graphics card that allows fewer than 1,000 pieces per model could still miss part of a vehicle. None has been reported so far.

## v1.4

This release is mostly about player feedback. After the airship came out, players sent us what bothered them: vehicles that looked strange to other players, balloons that kept climbing past the top of the world, a game mode switch that trapped the pilot, a bed in the Small Tent that put you outside, ladders you climbed by accident, invisible walls and flickering in the airship cabin. We took each report one at a time, fixed it, tried it in game, and kept only what actually worked. Some ideas were tried and taken back out, and they are listed at the end.

### Breaking changes from v1.3

- Airships placed with v1.3 are no longer recognised, because their lever block no longer exists. They cannot take off any more and are no longer locked. Empty the chests and workbenches, break the ship by hand and place a new one from a crate. A lever left in an inventory becomes an unknown item.
- An airship that was in flight when you updated is set down with the new layout of v1.4, so you do not lose it.
- Large Tents set up with v1.3 keep their climbable ladders and the cloth on the floor until you pack them and set them up again. When packed, that floor cloth may stay on the ground and has to be broken by hand.
- Several recipes changed: the large balloon crate needs the new large balloon burner, the tent crates need medium hide, the airship fuel tank needs heavy leather, and the burner, the airship engine, the burner outlet and the crates of the flying vehicles cost more. Crates and parts already crafted are not affected.
- For operators: the airship setting `tune leverIntangible` is now `tune helmIntangible`.

### Fixes from player feedback

- Changing game mode (creative and survival) while piloting or riding a balloon or the airship no longer traps you. You leave the vehicle, which is set down where it is or comes down by itself, and your movement is the one of your new mode.
- Balloons and the airship stop at the height limit of the world, with a message. A crate that would place something above the limit is refused and stays in your inventory.
- Other players now see a flying balloon or airship properly, with its flames, lights and sound. Only the pilot can pull the chain or the helm in flight.
- Passengers of the large balloon now stay on their seat the same way as on the airship.
- Pulling the take-off chain now always takes off the balloon it belongs to, even with another balloon parked nearby.
- In the Small Tent, getting up from the bed or waking up puts you just outside the entrance, facing away from the tent. If your respawn point is the bed, you respawn there too. Tents already set up benefit from it.
- The ladders at the entrance of the Large Tent are decoration only. You can no longer climb them by mistake.
- The Large Tent no longer has an orange cloth lying on the floor between the beds.
- In the airship, the inkwell, the chair and the stools of the cabin no longer block you. You can still sit on them.
- The anvil, the armour bench and the basic workbench of the upper deck are rearranged so they no longer overlap and flicker. The stool moved in front of the workbench.
- The airship turns more smoothly, with a gentler start and stop.

### Cloudwork airship

- The lever is gone. You take off and land with the helm, the window at the bow in front of the pilot spot.
- The airship turns toward the direction you fly, and at rest toward where you look.
- A workbench level kept during the flight is only given back to a workbench of the same type.

### Animal transport balloon

- New balloon as large as the large one, with a green and white envelope, for a pilot and one passenger, with an iron cage hanging under the basket. It is placed, takes off, flies and lands like the other balloons.
- While the balloon hovers, the cage lever lowers the cage on its chain, up to 20 blocks, and raises it again. Pull it during the move to stop the cage. The gate lever opens and closes the front of the cage, once the cage rests on the ground. Take-off is refused while the cage is lowered or open.
- Closing the gate with passive animals inside (cows, sheep, pigs, chickens, turkeys, goats, horses, rabbits, deer and their young, wild or tamed, up to 3) catches them. They stay visible and protected in the cage, follow it up and down, fly with the balloon and are still there after landing or a restart. Opening the gate releases them.
- Lure an animal by dropping its favourite food in the cage: lettuce for cows and sheep, carrots for rabbits and horses, corn for chickens, apples for goats.

### Hot air balloons

- The large balloon has its own burner ("Large hot air balloon burner"), which its crate now needs instead of the shared burner. Balloons already placed are not affected.

### Recipes

- More wool in the crates of the four flying vehicles, in line with their size: large balloon 300 red and 250 white, small balloon 37 white and 25 blue, animal transport balloon 300 green and 250 white, airship 750 white.
- The animal transport balloon crate is crafted at a tier 2 basic workbench from a large balloon burner, a chain, 250 white wool, 300 green wool, 24 hardwood planks and 30 iron bars. It needs a clear space of 23 x 41 x 23 blocks.
- The large balloon burner is crafted at a tier 2 basic workbench from 12 iron bars, 6 cobalt bars, 4 thorium bars and 4 fire essences.
- Rebalanced. The burner costs 36 iron bars, 18 copper bars and 12 charcoal. The airship burner outlet costs 36 iron bars, 18 cobalt bars, 12 thorium bars and 12 fire essences. The airship engine costs 72 iron bars, 36 thorium bars, 40 adamantite bars, 60 charcoal and 30 fire essences. The airship fuel tank needs 6 heavy leather instead of medium leather. Both tent crates need medium hide instead of light hide (10 for the Small Tent, 20 for the Large Tent).

### What we could not do, and why

- A camera pulled back during flight. We tried a far view forced by the server, then a view you could switch to first person, then a zoom with the mouse wheel. The game does not tell the server which view you use and does not send the mouse wheel, so none of these could be made comfortable. The far view also made it hard to reach the chain and the helm. We removed it: you fly with the normal game view.
- Turning the airship with the side movement keys. The game does not send key presses to the server, so the key could only be guessed from how the pilot moved. In practice it turned your character's view before the ship. We went back to turning toward where you fly or look.
- A wider hatch with a ladder down to the lower deck of the airship. It turned out unusable, so the original hatch is back until we find a better design.
- A taller interior for the Small Tent, so you could stand up next to the bed. Moving you outside the entrance when you get up solves the same problem without changing the tent.

## v1.3

### Cloudwork airship

- New airship, a large ship that turns freely toward the direction you fly. Its crate ("Cloudwork airship crate") is crafted at a tier 3 basic workbench and needs an engine, a fuel tank, 2 burner outlets, all the workbenches the ship carries, wool, planks and iron.
- You take off by pulling the lever at the pilot spot, and you land by pulling the lever again in flight. The ship stays in the air without fuel. It only burns fuel while it moves sideways.
- The fuel tank sits between the two furnaces. The engine under it glows and lights up while the tank has fuel. Two burners at the back spit short flames and smoke when the ship moves, and only a light smoke at rest.
- The ship has 15 seats, 7 stools and 4 tavern benches of 2 places. Players on board at take-off are seated automatically and set down at landing.
- Workbench levels and chest contents are kept during the flight.
- Death, disconnection and server shutdown in flight are handled, and `/orbishorizon airship despawn` removes a ship and gives the crate back.
- The crate icon and sides show a small airship.

### Hot air balloons

- You now land by pulling the chain again in flight, as you take off. The double jump no longer stops the flight, for the balloons and for the airship.

### Known limits

- Most of the airship has been tested only in part. Passengers, the persistence of workbench levels and the lever in flight may still need adjusting.
- Recipe quantities are balance suggestions and may change.

## v1.2

### Hot air balloons

- New small hot air balloon for one person. It has its own crate ("Small hot air balloon crate"), crafted at the basic workbench (tier 2, Tinkering tab) from a burner, a chain, 37 white wool, 25 blue wool and 6 hardwood planks. It flies, refuels, hovers and lands like the large balloon, but has no seats and no chests.
- The flame and the flamethrower jet of the small balloon are half the size of the large one and stay straight inside the envelope when you move.
- A balloon now carries as many people as it has seats, plus the pilot: 5 for the large balloon, 1 for the small one. If too many players are in the basket, take-off is refused and the message tells you how many must get off.
- `/orbishorizon balloon despawn` gives back the crate of the balloon it removed (small or large).

### Recipes

- The balloon recipe is no longer sold by the Kweebec merchant of the Forgotten Temple. Nothing needs to be learnt any more, and the merchant offers the game's usual trades again.
- The burner, the chain and the small balloon crate need a tier 2 basic workbench. The large balloon crate still needs tier 3.

### Tents

- New Large Tent with 2 beds, 2 chests, 2 stools, 2 candles, a campfire and ladders at the entrance. Its crate ("Large Tent") is crafted at the basic workbench (tier 2, Tinkering tab) from 80 fibre, 20 light hide, 30 sticks, a campfire, 2 crude beds and 2 small crude chests.
- It is set up and packed like the Small Tent, with its own empty crate ("Large Tent (empty crate)"). When packed, the contents of the campfire and of both chests drop to the ground.
- The empty crate of one tent does not fit the other one. A message says so and nothing changes.

### Known limits

- The small balloon and the Large Tent have not been tested in game yet. The position and facing of the Large Tent when it is set up may still need adjusting.
- A player climbing a ladder of the Large Tent when it is packed is not moved first and may fall.
- Recipe quantities are balance suggestions and may change.

## v1.1.1

### Hot air balloon

- The balloon now stops at the surface of water and other liquids (lava, poison, slime and tar included). Its blocks no longer sink under the surface, whether you fly down, double jump to set it down, or run out of fuel and descend on its own.
- When the balloon is set down just above a liquid, the ladder stops at the surface and the liquid around and under the basket is left untouched.
- Players who are set back on the ground when their balloon is removed, or when a passenger disconnects in flight, now land on the surface of a liquid instead of at the bottom.
- Fire does not count as a liquid, so flying through a fire is not blocked.

### Language

- Every chat message from the mod (take-off, fuel warnings, landing, locked structures, tent, recipes and admin commands) is now translated. You see it in French if the game language is French, and in English otherwise. Before, these messages were always in French.
- The descriptions of the `/orbishorizon` commands in the help and in the command completion are translated the same way.
- The French message shown when the burner is empty now correctly says the burner is above the chain, not under it.
- The output of `/orbishorizon balloon debug` is now in English for everyone.

### Known limits

- A balloon deployed from its crate on the bottom of a lake is still placed there, and the water inside its volume is removed.
