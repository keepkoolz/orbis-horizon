# Changelog

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
