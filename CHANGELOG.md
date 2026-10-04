# Changelog

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
