# Changelog

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

- New small hot air balloon for one person. It has its own crate ("Small hot air balloon crate"), crafted at the basic workbench (tier 2, Tinkering tab) from a burner, a chain, 16 white wool, 12 blue wool and 6 hardwood planks. It flies, refuels, hovers and lands like the large balloon, but has no seats and no chests.
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
