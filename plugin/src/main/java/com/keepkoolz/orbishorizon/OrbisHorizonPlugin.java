package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import com.keepkoolz.orbishorizon.api.OrbisHorizonApi;

import java.util.logging.Level;

/**
 * Entry point of the balloon mod (the "Main" field of manifest.json).
 */
public class OrbisHorizonPlugin extends JavaPlugin {

    public OrbisHorizonPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // The public API first: extensions register during their own setup, after this plugin's.
        OrbisHorizonApi.install(new OrbisHorizonApi.Services(new ApiStations(), new ApiStationKinds(), new ApiWorldAccess(),
                new ApiTerrain(), new ApiTelemetry(), getDataDirectory(), e -> Extensions.onRegistered(e, Extensions::startedWorlds)));
        // Interaction type usable in item JSONs: "Type": "HotairBalloon_Deploy"
        getCodecRegistry(Interaction.CODEC)
                .register(DeployBalloonInteraction.TYPE_ID, DeployBalloonInteraction.class, DeployBalloonInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(DeployTentInteraction.TYPE_ID, DeployTentInteraction.class, DeployTentInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(ExchangeCrateInteraction.TYPE_ID, ExchangeCrateInteraction.class, ExchangeCrateInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PlaceMasterInteraction.TYPE_ID, PlaceMasterInteraction.class, PlaceMasterInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PlaceQuayInteraction.TYPE_ID, PlaceQuayInteraction.class, PlaceQuayInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PlaceArrivalInteraction.TYPE_ID, PlaceArrivalInteraction.class, PlaceArrivalInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(StationMasterInteraction.TYPE_ID, StationMasterInteraction.class, StationMasterInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(DeployCampInteraction.TYPE_ID, DeployCampInteraction.class, DeployCampInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PackCampInteraction.TYPE_ID, PackCampInteraction.class, PackCampInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(ExamineFurnitureInteraction.TYPE_ID, ExamineFurnitureInteraction.class, ExamineFurnitureInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(OpenJournalInteraction.TYPE_ID, OpenJournalInteraction.class, OpenJournalInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(SkiffPackInteraction.TYPE_ID, SkiffPackInteraction.class, SkiffPackInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PackTentInteraction.TYPE_ID, PackTentInteraction.class, PackTentInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(TakeOffBalloonInteraction.TYPE_ID, TakeOffBalloonInteraction.class, TakeOffBalloonInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(LandBalloonInteraction.TYPE_ID, LandBalloonInteraction.class, LandBalloonInteraction.CODEC);
        // levers of the transport balloon (cage up and down, cage side open and closed).
        getCodecRegistry(Interaction.CODEC)
                .register(CageLeverInteraction.TYPE_ID, CageLeverInteraction.class, CageLeverInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(CageGateInteraction.TYPE_ID, CageGateInteraction.class, CageGateInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(AirshipLadderLeverInteraction.TYPE_ID, AirshipLadderLeverInteraction.class, AirshipLadderLeverInteraction.CODEC);
        getEntityStoreRegistry().registerSystem(new BalloonFlightSystem());
        getCodecRegistry(Interaction.CODEC)
                .register(AirshipTakeOffInteraction.TYPE_ID, AirshipTakeOffInteraction.class, AirshipTakeOffInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(AirshipCrewTalkInteraction.TYPE_ID, AirshipCrewTalkInteraction.class, AirshipCrewTalkInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(AirshipDeskInteraction.TYPE_ID, AirshipDeskInteraction.class, AirshipDeskInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(AirshipHelmInteraction.TYPE_ID, AirshipHelmInteraction.class, AirshipHelmInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(AirshipHireInteraction.TYPE_ID, AirshipHireInteraction.class, AirshipHireInteraction.CODEC);
        registerAirshipSystems();
        try {
            // Burner flame on the ground according to the burner's fuel. Needs the game's
            // crafting module (ProcessingBenchBlock), loaded before the mods (hypothesis: the game's
            // plugins are loaded first, "Loading pending core plugins" of PluginManager).
            getChunkStoreRegistry().registerSystem(new BurnerFlameSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Ground burner flame disabled");
        }
        try {
            // the fuel of burners placed before the tannery-style interface moves from the fuel container to the inputs,
            // before the game reduces the former to a capacity of 0 (and drops what does not fit).
            getChunkStoreRegistry().registerSystem(new BurnerMigrationSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Move of the old burner fuel disabled");
        }
        try {
            // No camping pack in the chests of a mod tent: slot filters are not saved, reinstalled when a chest appears.
            getChunkStoreRegistry().registerSystem(new CampChestFilter());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Tent chest filter disabled");
        }
        // resume files in the plugin data folder, landing on pilot disconnection and on
        // server shutdown, resume when worlds load.
        BalloonResume.init(getDataDirectory());
        AirshipResume.init(getDataDirectory());
        // registry of placed balloons and locking (breaking, break damage and block placement cancelled).
        BalloonRegistry.init(getDataDirectory());
        VehicleNames.init(getDataDirectory());
        DiscoveryStore.init(getDataDirectory());
        // Transit: station network (stations.json).
        TransitNetwork.get().load(getDataDirectory());
        TransitFare.loadRefunds(getDataDirectory());
        deleteStoredRoutes(getDataDirectory());
        SeaCharts.start(getDataDirectory());
        TransitSettings.load(getDataDirectory());
        ConsoleLogs.init(getLogger());
        ConsoleLogs.apply(TransitSettings.consoleLogLevel());
        PassengerProtection.load(getDataDirectory());
        CampRespawn.load(getDataDirectory());
        KnownStations.init(getDataDirectory());
        try {
            getEntityStoreRegistry().registerSystem(new BalloonLockSystems.Break());
            getEntityStoreRegistry().registerSystem(new BalloonLockSystems.Damage());
            getEntityStoreRegistry().registerSystem(new BalloonLockSystems.Place());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Balloon locking disabled");
        }
        // a pilot or passenger who dies in flight leaves the balloon, which descends on its own if it is the pilot.
        try {
            getEntityStoreRegistry().registerSystem(new BalloonDeathSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Pilot death in flight: system disabled, only the tick check remains");
        }
        // a player who gets up from the small tent's bed is put at the tent exit, the bed's respawn point too.
        try {
            getEntityStoreRegistry().registerSystem(new TentBedSystem());
            getEntityStoreRegistry().registerSystem(new CampRespawn.RespawnWatcher());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Leaving the tent bed: system disabled");
        }
        // upgrading a camp pack keeps the contents of the lower pack (craft events).
        try {
            getEntityStoreRegistry().registerSystem(new CampUpgrade.Pre());
            getEntityStoreRegistry().registerSystem(new CampUpgrade.Post());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Camp bag upgrade: system disabled");
        }
        // disabled recipes of the mod cannot be crafted (craft events).
        try {
            getEntityStoreRegistry().registerSystem(new RecipeToggles.Guard());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Disabled recipes: craft blocking disabled");
        }
        // bench tabs whose recipes are all disabled are removed from the window data.
        try {
            BenchTabs.register();
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Disabled recipes: bench tab hiding disabled");
        }
        // a game mode change of a pilot or passenger makes them leave the vehicle (the game resets the movement settings).
        try {
            getEntityStoreRegistry().registerSystem(new GameModeSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Leaving the vehicle on game mode change: system disabled");
        }
        // Passengers aboard cannot be damaged (the Intangible marker is handled by PassengerProtection.protect).
        try {
            getEntityStoreRegistry().registerSystem(new PassengerProtection.DamageFilter());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Passenger damage protection disabled");
        }
        // the camp pack in the inventory is drawn on the player's back (look rebuilt as attachments).
        try {
            getEntityStoreRegistry().registerSystem(new PackBackView.SkinWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.SettingsWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.ModelWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.InventoryWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.RespawnWatch());
            getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                    event -> PackBackView.onReady(event.getPlayerRef()));
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Camp bag on the back disabled");
        }
        try {
            getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                    event -> {
                        TransitFare.onReady(event.getPlayerRef());
                        StationContracts.onReady(event.getPlayerRef());
                    });
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Ticket refunds and first station contract on arrival disabled");
        }
        // dual rendering of the vehicles in flight needs the per-viewer visibility filter. Without it every flight keeps the single entity.
        try {
            getEntityStoreRegistry().registerSystem(new ViewFilter.FilterSystem());
            ViewFilter.markRegistered();
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Double display of flying vehicles: visibility filter disabled, one entity per vehicle");
        }
        // a passenger who disconnects is dismounted cleanly (they would otherwise be saved in the air).
        getEventRegistry().register(PlayerDisconnectEvent.class,
                event -> {
                    BalloonManager.get().onPlayerGone(event.getPlayerRef().getUuid());
                    AirshipManager.get().onPlayerGone(event.getPlayerRef().getUuid());
                    StationNamePage.onPlayerGone(event.getPlayerRef().getUuid());
                    VehicleNamePage.onPlayerGone(event.getPlayerRef().getUuid());
                    HeldCratePreview.onPlayerGone(event.getPlayerRef().getUuid());
                    DiscoveryStore.forget(event.getPlayerRef().getUuid());
                });
        // a passenger left in the air when a flight was resumed is put down on their next arrival.
        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class,
                event -> {
                    BalloonManager.get().onPlayerAdded(event.getHolder(), event.getWorld());
                    // Mod recipes follow the server setting (the disabled ones are hidden on the client).
                    try {
                        RecipeToggles.syncOnArrival(event.getHolder());
                    } catch (RuntimeException e) {
                        getLogger().at(Level.WARNING).withCause(e).log("Mod recipes not synced on arrival");
                        Telemetry.error("recipes", e, null, null);
                    }
                });
        // Before the players are disconnected (-48) and the worlds stopped (-32): the worlds are still running.
        getEventRegistry().register((short) -50, ShutdownEvent.class, event -> {
            BalloonManager.get().landAll();
            AirshipManager.get().landAll();
            BalloonRegistry.flush();
            DiscoveryStore.flush();
            Telemetry.stop();
        });
        // World map markers of the stations, registered on every world like the game's warps.
        getEventRegistry().registerGlobal(AddWorldEvent.class, event -> {
            try {
                StationMarkerProvider.register(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Station map markers not registered");
            }
            try {
                OwnedMarkerProvider.register(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Vehicle map markers not registered");
            }
            try {
                RespawnPointMarkerProvider.register(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Respawn point map markers not registered");
            }
        });
        getEventRegistry().registerGlobal(StartWorldEvent.class, event -> {
            BalloonManager.get().recoverWorld(event.getWorld());
            Extensions.worldStart(event.getWorld());
            SeaCharts.onWorldStart(event.getWorld());
            CageAnimals.recoverWorld(event.getWorld());
            StationMaster.recoverWorld(event.getWorld());
            StationQueue.recoverWorld(event.getWorld());
            BoatQuay.recoverWorld(event.getWorld());
            try {
                AirshipCrew.recoverWorld(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the pilots of %s failed", event.getWorld().getName());
            }
            AirshipManager.get().recoverWorld(event.getWorld());
            BalloonRecall.recoverWorld(event.getWorld());
        });
        // A world being removed (another mod may cancel the removal: the extension tolerates a stop without a removal).
        getEventRegistry().registerGlobal(RemoveWorldEvent.class, event -> Extensions.worldStop(event.getWorld()));
    }

    @Override
    protected void start() {
        PackBackView.detectOtherBackpackMod(); // once, result logged
        // the tent shape is read once (asset prefabs are loaded before start). A failure
        // is logged by Deployables and the shape will be read again on demand.
        for (Deployables.Kind kind : Deployables.TENTS) {
            kind.shapeOrNull();
        }
        // Airship prototype: the shape is read once and the collision hull computed (logged by AirshipManager).
        for (Deployables.Kind shipKind : Deployables.SHIPS) {
            StructureShape airship = shipKind.shapeOrNull();
            if (airship != null) {
                AirshipManager.get().warmUp(shipKind, airship);
            }
        }
        // balloon types, with their number of seats (limit of passengers).
        for (Deployables.Kind kind : Deployables.BALLOONS) {
            kind.shapeOrNull();
        }
        // Transit network: the transit balloon (not in BALLOONS) and the stations.
        if (Deployables.BALLOON_PUBLIC.shapeOrNull() == null) {
            getLogger().at(Level.WARNING).log("Balloon '%s': unreadable prefab", Deployables.BALLOON_PUBLIC.id);
        }
        if (Deployables.STATION.shapeOrNull() == null) {
            getLogger().at(Level.WARNING).log("Station: unreadable prefab");
        }
        if (Deployables.BOAT_STATION.shapeOrNull() == null) {
            getLogger().at(Level.WARNING).log("Sea station: unreadable prefab");
        }
        // Worlds already started when the plugin starts (StartWorldEvent has already passed).
        for (World world : Universe.get().getWorlds().values()) {
            try {
                StationMarkerProvider.register(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Station map markers not registered for %s", world.getName());
            }
            try {
                OwnedMarkerProvider.register(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Vehicle map markers not registered for %s", world.getName());
            }
            try {
                RespawnPointMarkerProvider.register(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Respawn point map markers not registered for %s", world.getName());
            }
            Extensions.worldStart(world);
            SeaCharts.onWorldStart(world);
            // One failing resume must never stop the plugin: StartWorldEvent calls them again when the world is ready.
            try {
                BalloonManager.get().recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the balloons of %s deferred to world start", world.getName());
            }
            try {
                CageAnimals.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the animals of %s deferred to world start", world.getName());
            }
            try {
                StationMaster.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the station masters of %s deferred to world start", world.getName());
            }
            try {
                AirshipCrew.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the pilots of %s deferred to world start", world.getName());
            }
            try {
                StationQueue.recoverWorld(world);
                BoatQuay.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the docks of %s deferred to world start", world.getName());
            }
            try {
                AirshipManager.get().recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the airships of %s deferred to world start", world.getName());
            }
            try {
                BalloonRecall.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Resume of the recalled balloons of %s deferred to world start", world.getName());
            }
        }
        Extensions.markStarted();
        // Every extension has registered during setup, so the command tree is complete: addSubCommand refuses a registered command.
        getCommandRegistry().registerCommand(new OrbisHorizonCommand());
        Telemetry.start(this); // last: Beacon starts after the normal setup
    }

    @Override
    protected void shutdown() {
        BalloonManager.get().landAll();
        AirshipManager.get().landAll();
        BalloonRegistry.stopWriter();
        DiscoveryStore.flush();
        SeaCharts.stop();
        BenchTabs.unregister();
        Telemetry.stop();
    }

    /** Deletes the file of stored boat routes (and its .bad copy), which is not read any more. */
    private void deleteStoredRoutes(java.nio.file.Path dataDir) {
        for (String name : new String[] {"boat-routes.json", "boat-routes.json.bad"}) {
            try {
                java.nio.file.Files.deleteIfExists(dataDir.resolve(name));
            } catch (java.io.IOException | RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("%s: cannot delete", name);
            }
        }
    }

    /** Airship: the head-yaw reader (before ProcessPlayerInput) and the flight tick. A failure is logged and the system is skipped. */
    private void registerAirshipSystems() {
        try {
            getEntityStoreRegistry().registerSystem(new AirshipInputSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Airship: input reader disabled, the 'look' heading mode will not work");
        }
        try {
            getEntityStoreRegistry().registerSystem(new AirshipFlightSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Airship: flight tick disabled");
        }
    }
}
