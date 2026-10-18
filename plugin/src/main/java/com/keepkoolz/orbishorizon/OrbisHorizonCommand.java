package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * Root command /orbishorizon. Commands specific to one of the mod's structure types are grouped under its name:
 * /orbishorizon balloon <command> for the hot air balloon (BalloonCommand). The root is open to everyone (requireNoPermission, needed so
 * that open sub-commands are reachable). Every group and sub-command except "map" is restricted to ops (hytale:Admin).
 */
public final class OrbisHorizonCommand extends AbstractCommandCollection {

    public OrbisHorizonCommand() {
        super("orbishorizon", Texts.cmd("root.desc"));
        requireNoPermission();
        addSubCommand(new BalloonCommand());
        addSubCommand(new AirshipCommand());
        addSubCommand(new StationCommand());
        addSubCommand(new PreviewCommand());
        addSubCommand(new PassengersCommand());
        addSubCommand(new MapCommand());
        addSubCommand(new CampingCommand());
    }
}
