package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

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
        // Interaction type usable in item JSONs: "Type": "HotairBalloon_Deploy"
        getCodecRegistry(Interaction.CODEC)
                .register(DeployBalloonInteraction.TYPE_ID, DeployBalloonInteraction.class, DeployBalloonInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(DeployTentInteraction.TYPE_ID, DeployTentInteraction.class, DeployTentInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(DeployStationInteraction.TYPE_ID, DeployStationInteraction.class, DeployStationInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(StationMasterInteraction.TYPE_ID, StationMasterInteraction.class, StationMasterInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(DeployCampInteraction.TYPE_ID, DeployCampInteraction.class, DeployCampInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PackCampInteraction.TYPE_ID, PackCampInteraction.class, PackCampInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(PackTentInteraction.TYPE_ID, PackTentInteraction.class, PackTentInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(TakeOffBalloonInteraction.TYPE_ID, TakeOffBalloonInteraction.class, TakeOffBalloonInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(LandBalloonInteraction.TYPE_ID, LandBalloonInteraction.class, LandBalloonInteraction.CODEC);
        // T73: levers of the transport balloon (cage up and down, cage side open and closed).
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
            // Burner flame on the ground according to the burner's fuel (T13). Needs the game's
            // crafting module (ProcessingBenchBlock), loaded before the mods (hypothesis: the game's
            // plugins are loaded first, "Loading pending core plugins" of PluginManager).
            getChunkStoreRegistry().registerSystem(new BurnerFlameSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Flamme du brûleur au sol désactivée");
        }
        try {
            // T41: the fuel of burners placed before T41 moves from the fuel container to the inputs,
            // before the game reduces the former to a capacity of 0 (and drops what does not fit).
            getChunkStoreRegistry().registerSystem(new BurnerMigrationSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Déplacement de l'ancien combustible des brûleurs désactivé");
        }
        try {
            // No camping pack in the chests of a mod tent: slot filters are not saved, reinstalled when a chest appears.
            getChunkStoreRegistry().registerSystem(new CampChestFilter());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Filtre des coffres de tente désactivé");
        }
        // T19: resume files in the plugin data folder, landing on pilot disconnection and on
        // server shutdown, resume when worlds load.
        BalloonResume.init(getDataDirectory());
        AirshipResume.init(getDataDirectory());
        // T27: registry of placed balloons and locking (breaking, break damage and block placement cancelled).
        BalloonRegistry.init(getDataDirectory());
        // Transit: station network (stations.json).
        TransitNetwork.get().load(getDataDirectory());
        TransitSettings.load(getDataDirectory());
        PassengerProtection.load(getDataDirectory());
        CampRespawn.load(getDataDirectory());
        try {
            getEntityStoreRegistry().registerSystem(new BalloonLockSystems.Break());
            getEntityStoreRegistry().registerSystem(new BalloonLockSystems.Damage());
            getEntityStoreRegistry().registerSystem(new BalloonLockSystems.Place());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Verrouillage des montgolfières désactivé");
        }
        // T39: a pilot or passenger who dies in flight leaves the balloon, which descends on its own if it is the pilot.
        try {
            getEntityStoreRegistry().registerSystem(new BalloonDeathSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Mort du pilote en vol : système désactivé, seul le contrôle du tick reste");
        }
        // T93: a player who gets up from the small tent's bed is put at the tent exit, the bed's respawn point too.
        try {
            getEntityStoreRegistry().registerSystem(new TentBedSystem());
            getEntityStoreRegistry().registerSystem(new CampRespawn.RespawnWatcher());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Sortie du lit de la tente : système désactivé");
        }
        // T156: upgrading a camp pack keeps the contents of the lower pack (craft events).
        try {
            getEntityStoreRegistry().registerSystem(new CampUpgrade.Pre());
            getEntityStoreRegistry().registerSystem(new CampUpgrade.Post());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Amélioration des sacs de campement : système désactivé");
        }
        // T75: a game mode change of a pilot or passenger makes them leave the vehicle (the game resets the movement settings).
        try {
            getEntityStoreRegistry().registerSystem(new GameModeSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Sortie du véhicule au changement de mode de jeu : système désactivé");
        }
        // Passengers aboard cannot be damaged (the Intangible marker is handled by PassengerProtection.protect).
        try {
            getEntityStoreRegistry().registerSystem(new PassengerProtection.DamageFilter());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Protection des passagers contre les dégâts désactivée");
        }
        // T157: the camp pack in the inventory is drawn on the player's back (look rebuilt as attachments).
        try {
            getEntityStoreRegistry().registerSystem(new PackBackView.SkinWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.SettingsWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.ModelWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.InventoryWatch());
            getEntityStoreRegistry().registerSystem(new PackBackView.RespawnWatch());
            getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                    event -> PackBackView.onReady(event.getPlayerRef()));
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Sac de campement sur le dos désactivé");
        }
        // T82: dual rendering of the vehicles in flight needs the per-viewer visibility filter. Without it every flight keeps the single entity.
        try {
            getEntityStoreRegistry().registerSystem(new ViewFilter.FilterSystem());
            ViewFilter.markRegistered();
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Affichage double des véhicules en vol : filtre de visibilité désactivé, une seule entité par véhicule");
        }
        // T21: a passenger who disconnects is dismounted cleanly (they would otherwise be saved in the air).
        getEventRegistry().register(PlayerDisconnectEvent.class,
                event -> {
                    BalloonManager.get().onPlayerGone(event.getPlayerRef().getUuid());
                    AirshipManager.get().onPlayerGone(event.getPlayerRef().getUuid());
                    StationNamePage.onPlayerGone(event.getPlayerRef().getUuid());
                    HeldCratePreview.onPlayerGone(event.getPlayerRef().getUuid());
                });
        // T21: a passenger left in the air when a flight was resumed is put down on their next arrival.
        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class,
                event -> {
                    BalloonManager.get().onPlayerAdded(event.getHolder(), event.getWorld());
                    // Transit: the station crate recipe follows the server setting.
                    try {
                        StationCommand.syncOnArrival(event.getHolder());
                    } catch (RuntimeException e) {
                        getLogger().at(Level.WARNING).withCause(e).log("Recette de la station non synchronisée à l'arrivée");
                    }
                });
        // Before the players are disconnected (-48) and the worlds stopped (-32): the worlds are still running.
        getEventRegistry().register((short) -50, ShutdownEvent.class, event -> {
            BalloonManager.get().landAll();
            AirshipManager.get().landAll();
            BalloonRegistry.flushCage();
            Telemetry.stop();
        });
        // World map markers of the stations, registered on every world like the game's warps.
        getEventRegistry().registerGlobal(AddWorldEvent.class, event -> {
            try {
                StationMarkerProvider.register(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Marqueurs de carte des stations non enregistrés");
            }
            try {
                OwnedMarkerProvider.register(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Marqueurs de carte des véhicules non enregistrés");
            }
        });
        getEventRegistry().registerGlobal(StartWorldEvent.class, event -> {
            BalloonManager.get().recoverWorld(event.getWorld());
            CageAnimals.recoverWorld(event.getWorld());
            StationMaster.recoverWorld(event.getWorld());
            StationQueue.recoverWorld(event.getWorld());
            try {
                AirshipCrew.recoverWorld(event.getWorld());
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des pilotes de %s en échec", event.getWorld().getName());
            }
            AirshipManager.get().recoverWorld(event.getWorld());
        });
        getCommandRegistry().registerCommand(new OrbisHorizonCommand());
        getLogger().at(Level.INFO).log("Orbis Horizon plugin: interaction %s, /orbishorizon balloon command and flight system registered",
                DeployBalloonInteraction.TYPE_ID);
    }

    @Override
    protected void start() {
        PackBackView.detectOtherBackpackMod(); // T157: once, result logged
        // T44: the tent shape is read once (asset prefabs are loaded before start). A failure
        // is logged by Deployables and the shape will be read again on demand.
        for (Deployables.Kind kind : Deployables.TENTS) {
            StructureShape tent = kind.shapeOrNull();
            if (tent != null) {
                StructureShape.Cell anchor = tent.anchor();
                getLogger().at(Level.INFO).log("%s : %d case(s), repère en (%d, %d, %d)",
                        kind == Deployables.TENT ? "Tente" : kind == Deployables.BEDROLL ? "Sac de couchage" : "Grande tente", tent.cells().size(), anchor.x(), anchor.y(), anchor.z());
            }
        }
        // Airship prototype: the shape is read once and the collision hull computed (logged by AirshipManager).
        for (Deployables.Kind shipKind : Deployables.SHIPS) {
            StructureShape airship = shipKind.shapeOrNull();
            if (airship != null) {
                getLogger().at(Level.INFO).log("%s : %d case(s), repère « %s » en (%d, %d, %d)",
                        AirshipManager.cap(shipKind.airship.logLabel), airship.cells().size(),
                        airship.anchor().baseName(), airship.anchor().x(), airship.anchor().y(), airship.anchor().z());
                AirshipManager.get().warmUp(shipKind, airship);
            }
        }
        // T54: balloon types, with their number of seats (limit of passengers).
        for (Deployables.Kind kind : Deployables.BALLOONS) {
            StructureShape shape = kind.shapeOrNull();
            if (shape != null) {
                getLogger().at(Level.INFO).log("Montgolfière « %s » : %d case(s), %d siège(s)", kind.id, shape.cells().size(), shape.seats().size());
            }
        }
        // Transit network: the transit balloon (not in BALLOONS) and the station, same startup log lines.
        StructureShape transit = Deployables.BALLOON_PUBLIC.shapeOrNull();
        if (transit != null) {
            getLogger().at(Level.INFO).log("Montgolfière « %s » : %d case(s), %d siège(s)", Deployables.BALLOON_PUBLIC.id,
                    transit.cells().size(), transit.seats().size());
        } else {
            getLogger().at(Level.WARNING).log("Montgolfière « %s » : prefab illisible", Deployables.BALLOON_PUBLIC.id);
        }
        StructureShape station = Deployables.STATION.shapeOrNull();
        if (station != null) {
            getLogger().at(Level.INFO).log("Station : %d case(s), repère en (%d, %d, %d)", station.cells().size(),
                    station.anchor().x(), station.anchor().y(), station.anchor().z());
        } else {
            getLogger().at(Level.WARNING).log("Station : prefab illisible");
        }
        // Worlds already started when the plugin starts (StartWorldEvent has already passed).
        for (World world : Universe.get().getWorlds().values()) {
            try {
                StationMarkerProvider.register(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Marqueurs de carte des stations non enregistrés pour %s", world.getName());
            }
            try {
                OwnedMarkerProvider.register(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Marqueurs de carte des véhicules non enregistrés pour %s", world.getName());
            }
            // One failing resume must never stop the plugin: StartWorldEvent calls them again when the world is ready.
            try {
                BalloonManager.get().recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des montgolfières de %s remise au démarrage du monde", world.getName());
            }
            try {
                CageAnimals.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des animaux de %s remise au démarrage du monde", world.getName());
            }
            try {
                StationMaster.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des chefs de gare de %s remise au démarrage du monde", world.getName());
            }
            try {
                AirshipCrew.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des pilotes de %s remise au démarrage du monde", world.getName());
            }
            try {
                StationQueue.recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des quais de %s remise au démarrage du monde", world.getName());
            }
            try {
                AirshipManager.get().recoverWorld(world);
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("Reprise des dirigeables de %s remise au démarrage du monde", world.getName());
            }
        }
        Telemetry.start(this); // last: Beacon starts after the normal setup
    }

    @Override
    protected void shutdown() {
        BalloonManager.get().landAll();
        AirshipManager.get().landAll();
        BalloonRegistry.flushCage();
        Telemetry.stop();
    }

    /** Airship: the head-yaw reader (before ProcessPlayerInput) and the flight tick. A failure is logged and the system is skipped. */
    private void registerAirshipSystems() {
        try {
            getEntityStoreRegistry().registerSystem(new AirshipInputSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Dirigeable : lecteur d'entrées désactivé, le mode de cap « look » ne marchera pas");
        }
        try {
            getEntityStoreRegistry().registerSystem(new AirshipFlightSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Dirigeable : tick de vol désactivé");
        }
        try {
            getEntityStoreRegistry().registerSystem(new CrewSeatSystem());
        } catch (RuntimeException e) {
            getLogger().at(Level.WARNING).withCause(e).log("Pilote : montage sur le tabouret désactivé");
        }
    }
}
