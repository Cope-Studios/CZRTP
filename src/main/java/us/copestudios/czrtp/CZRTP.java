package us.copestudios.czrtp;

import org.bukkit.plugin.java.JavaPlugin;
import us.copestudios.copecore.CopeCore;
import us.copestudios.copecore.config.ConfigFile;
import us.copestudios.copecore.text.Messages;

/**
 * CZRTP (Cope Zone Random Teleport): draw a zone, and players are teleported to a random safe spot inside it.
 *
 * <h2>How it fits together</h2>
 * <ul>
 *   <li>{@link Zones} - the zones (CopeCore regions in zones.yml) and corner setup</li>
 *   <li>{@link ZoneSettings} - delay, cooldown, prefix and messages, global and per zone (config.yml, messages.yml)</li>
 *   <li>{@link TeleportManager} - the countdown, cooldowns, safe-spot search and the teleport itself</li>
 *   <li>{@link CzrtpCommand} - /czrtp</li>
 *   <li>{@link LegacyMigration} - one-time upgrade from CZRTP 1.x (zones.yml, config_zone.yml)</li>
 * </ul>
 *
 * <h2>What CopeCore provides</h2>
 * <p>Colors and texts (MiniMessage + old &amp; codes), config loading with repair, the command framework and help page,
 * regions and the selection wand, cooldowns, the safe-spot finder (with the Nether roof fix), shared player states
 * (no teleporting out of a duel or combat), and PlaceholderAPI placeholders.</p>
 */
public final class CZRTP extends JavaPlugin {

    /** CopeCore API this version needs. */
    private static final int REQUIRED_CORE_API = 2;

    private ConfigFile config;
    private Messages messages;
    private Zones zones;
    private ZoneSettings settings;
    private TeleportManager teleports;

    @Override
    public void onEnable() {
        if (!CopeCore.requireApi(this, REQUIRED_CORE_API)) {
            return; // CopeCore printed what's wrong and disabled us
        }

        // 1) Upgrading from 1.x: must run BEFORE the new files are opened (old files share their names).
        LegacyMigration migration = new LegacyMigration(this);
        migration.prepareFiles();

        // 2) Files (ConfigFile creates them, adds new settings, repairs broken ones).
        config = new ConfigFile(this, "config.yml");
        messages = CopeCore.messages(this);

        // 3) Features.
        zones = new Zones(this);
        settings = new ZoneSettings(this);
        teleports = new TeleportManager(this);
        migration.importOldData();

        getServer().getPluginManager().registerEvents(teleports, this);
        new CzrtpCommand(this).register();
        Placeholders.register(this);

        getLogger().info("CZRTP enabled - " + zones.names().size() + " zone(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (teleports != null) {
            teleports.cancelAll();
        }
        // Zones and settings are saved the moment they change, so there is nothing to save here.
    }

    /** /czrtp reload: config.yml, messages.yml and zones.yml. Pending teleports and cooldowns are kept. */
    public void reload() {
        config.reload();
        messages.reload();
        zones.reload();
    }

    /** config.yml */
    public ConfigFile settingsFile() {
        return config;
    }

    /** messages.yml */
    public Messages messages() {
        return messages;
    }

    public Zones zones() {
        return zones;
    }

    public ZoneSettings settings() {
        return settings;
    }

    public TeleportManager teleports() {
        return teleports;
    }
}
