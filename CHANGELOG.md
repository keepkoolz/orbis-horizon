# Changelog

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
