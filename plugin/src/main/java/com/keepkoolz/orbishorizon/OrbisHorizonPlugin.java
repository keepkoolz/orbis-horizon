package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
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
                .register(PackTentInteraction.TYPE_ID, PackTentInteraction.class, PackTentInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(TakeOffBalloonInteraction.TYPE_ID, TakeOffBalloonInteraction.class, TakeOffBalloonInteraction.CODEC);
        getCodecRegistry(Interaction.CODEC)
                .register(LandBalloonInteraction.TYPE_ID, LandBalloonInteraction.class, LandBalloonInteraction.CODEC);
        getEntityStoreRegistry().registerSystem(new BalloonFlightSystem());
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
        // T19: resume files in the plugin data folder, landing on pilot disconnection and on
        // server shutdown, resume when worlds load.
        BalloonResume.init(getDataDirectory());
        // T27: registry of placed balloons and locking (breaking, break damage and block placement cancelled).
        BalloonRegistry.init(getDataDirectory());
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
        // T21: a passenger who disconnects is dismounted cleanly (they would otherwise be saved in the air).
        getEventRegistry().register(PlayerDisconnectEvent.class,
                event -> BalloonManager.get().onPlayerGone(event.getPlayerRef().getUuid()));
        // T21: a passenger left in the air when a flight was resumed is put down on their next arrival.
        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class,
                event -> BalloonManager.get().onPlayerAdded(event.getHolder(), event.getWorld()));
        // Before the players are disconnected (-48) and the worlds stopped (-32): the worlds are still running.
        getEventRegistry().register((short) -50, ShutdownEvent.class, event -> BalloonManager.get().landAll());
        getEventRegistry().registerGlobal(StartWorldEvent.class, event -> BalloonManager.get().recoverWorld(event.getWorld()));
        getCommandRegistry().registerCommand(new OrbisHorizonCommand());
        getLogger().at(Level.INFO).log("Orbis Horizon plugin: interaction %s, /orbishorizon balloon command and flight system registered",
                DeployBalloonInteraction.TYPE_ID);
    }

    @Override
    protected void start() {
        // T44: the tent shape is read once (asset prefabs are loaded before start). A failure
        // is logged by Deployables and the shape will be read again on demand.
        for (Deployables.Kind kind : Deployables.TENTS) {
            BalloonShape tent = kind.shapeOrNull();
            if (tent != null) {
                BalloonShape.Cell anchor = tent.anchor();
                getLogger().at(Level.INFO).log("%s : %d case(s), repère en (%d, %d, %d)",
                        kind == Deployables.TENT ? "Tente" : "Grande tente", tent.cells().size(), anchor.x(), anchor.y(), anchor.z());
            }
        }
        // T54: balloon types, with their number of seats (limit of passengers).
        for (Deployables.Kind kind : Deployables.BALLOONS) {
            BalloonShape shape = kind.shapeOrNull();
            if (shape != null) {
                getLogger().at(Level.INFO).log("Montgolfière « %s » : %d case(s), %d siège(s)", kind.id, shape.cells().size(), shape.seats().size());
            }
        }
        // Worlds already started when the plugin starts (StartWorldEvent has already passed).
        for (World world : Universe.get().getWorlds().values()) {
            BalloonManager.get().recoverWorld(world);
        }
    }

    @Override
    protected void shutdown() {
        BalloonManager.get().landAll();
    }
}
