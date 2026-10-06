package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;

/**
 * Root command /orbishorizon. Commands specific to one of the mod's structure types are grouped under its name:
 * /orbishorizon balloon <command> for the hot air balloon (BalloonCommand). Everything is restricted to ops (hytale:Admin).
 */
public final class OrbisHorizonCommand extends AbstractCommandCollection {

    public OrbisHorizonCommand() {
        super("orbishorizon", Texts.cmd("root.desc"));
        setPermissionGroups(HytalePermissionsProvider.OP_GROUP);
        addSubCommand(new BalloonCommand());
        addSubCommand(new AirshipCommand());
    }
}
