# Orbis Horizon

Orbis Horizon is an adventure mod for Hytale. It adds vehicles and camping gear that you craft, set up from a crate and pack away again. The mod currently contains a hot air balloon you can fly and a camping tent. More flying vehicles (a single-seater, an airship) and more camping gear are planned, but none of them have been started yet.

The mod is still in development. Several features have not been tested in game yet.

## The hot air balloon

You craft a deployment crate and place it on the ground, and a hot air balloon made of blocks appears. You then pull the burner chain to take off and fly freely through the air.

### How it works in game

1. Learn the recipes with the hot air balloon recipe item. The Kweebec merchant in the Forgotten Temple sells it for 10 concentrated life essences (`Ingredient_Life_Essence_Concentrated`).
2. At the basic workbench, upgraded to tier 3, open the Tinkering tab and craft the burner, the chain and then the deployment crate.
3. Use the secondary click to place the crate on the ground. The balloon appears in blocks, facing the player. After that it cannot be modified, so no block can be broken or added.
4. Put fuel in the burner, climb the ladder into the basket and pull the chain to take off.
5. Fly freely, moving forwards, up and down. A double jump stops the flight and sets the balloon back down in blocks where it is, on the ground or in the air.

Other behaviour:

- The burner uses up its fuel while flying and while hovering in the air. When it runs dry, the balloon descends on its own until it reaches the ground.
- The chests in the basket keep their contents during the flight.
- Up to 4 passengers sit on the stools during the flight. With more people on board, take-off is refused.
- If the pilot leaves the game during a flight, the balloon is set back down in blocks.
- Operators can use the admin command `/orbishorizon balloon despawn` (or `/orbishorizon balloon remove`) to remove the nearest balloon, whether it is placed or flying. The contents of the burner and the chests drop to the ground and the admin receives the deployment crate.

## The camping tent

- The "Small Tent" crate is crafted at the basic workbench (tier 1, Tinkering tab) from 40 fibre, 10 light hide, 20 sticks, a campfire and a bed. No recipe needs to be learnt. These quantities are suggestions and may change.
- A secondary click with the crate on the ground sets up the tent facing the player, with a bed, a campfire, a stool and a lantern. The tent needs enough free space, otherwise a message says so and the crate is left untouched.
- Once the tent is up, the crate in the player's hand becomes the "Small Tent (empty crate)", in the same inventory slot.
- A secondary click with the empty crate on the tent packs it away and the crate becomes full again. Only the player who set up the tent or an operator can pack it. Anything inside the campfire drops to the ground.
- While the tent is set up, it cannot be broken and no block can be placed on it. The bed and the campfire can still be used.

## Changelog

The changes of each version are listed in [CHANGELOG.md](CHANGELOG.md).

## Credits and sources

- The hot air balloon prefab is based on [Hot Air Ballon](https://www.curseforge.com/hytale/prefabs/hot-air-ballon). It has been modified for this mod (burner, take-off chain, ladder, removed blocks). Check the reuse terms on its page before distributing the mod.
- The tents prefab are based on [Desert Base Camp - Tent (Model 2)](https://www.curseforge.com/hytale/prefabs/desert-base-camp-tent-model-2) and [Desert Base Camp - Tent (Model 1)](https://www.curseforge.com/hytale/prefabs/desert-base-camp-tent). They have been modified for this mod (added/moved/removed blocks). Check the reuse terms on its page before distributing the mod.
- The 3D flight model and its texture are generated from the prefab using the game's block textures. These textures, and the game assets copied or adapted for the mod (merchant, shop), belong to Hypixel Studios.

## Licence

The original code and content of this mod are licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). You can use, copy and modify them for noncommercial purposes, and commercial use is not permitted. The prefabs and the game assets listed in [NOTICE](NOTICE) are not covered by this licence.

### Terms of use for this mod in your adventures.

If you use this mod in a modpack, on your server or elsewhere, please provide the URL of this mod’s page. 
The commercial distribution of content containing all or part of this mod is prohibited.