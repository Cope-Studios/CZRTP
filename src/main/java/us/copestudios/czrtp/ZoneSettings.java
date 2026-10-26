package us.copestudios.czrtp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import us.copestudios.copecore.text.Messages;

import java.util.List;
import java.util.Locale;

/**
 * Delay, cooldown, prefix and teleport messages, with per-zone overrides.
 *
 * <p>Lookup order for every value: {@code config.yml zones.<zone>.<key>} → the global value
 * ({@code config.yml settings.<key>} for numbers, messages.yml for texts).</p>
 *
 * <p>The three teleport messages are named start, cooldown and success (messages.yml "teleport.start" etc.).</p>
 */
public final class ZoneSettings {

    /** The teleport messages a zone can override. */
    public static final List<String> MESSAGE_KEYS = List.of("start", "cooldown", "success");

    private final CZRTP plugin;

    ZoneSettings(CZRTP plugin) {
        this.plugin = plugin;
    }

    private YamlConfiguration config() {
        return plugin.settingsFile().get();
    }

    /** The zone's own section in config.yml ("zones.<zone>"), or null if it has no overrides. */
    private ConfigurationSection zoneSection(String zone) {
        return zone == null ? null : config().getConfigurationSection("zones." + key(zone));
    }

    // =====================================================================
    //  Numbers and switches
    // =====================================================================

    public int delay(String zone) {
        return Math.max(0, number(zone, "teleport-delay", 3));
    }

    public int cooldown(String zone) {
        return Math.max(0, number(zone, "cooldown", 60));
    }

    private int number(String zone, String key, int fallback) {
        ConfigurationSection section = zoneSection(zone);
        if (section != null && section.contains(key)) {
            return section.getInt(key);
        }
        return config().getInt("settings." + key, fallback);
    }

    public boolean cancelOnMove() {
        return config().getBoolean("settings.cancel-on-move", false);
    }

    public List<String> blockedStates() {
        return config().getStringList("settings.blocked-states");
    }

    public int searchAttempts() {
        return Math.max(1, config().getInt("settings.search-attempts", 50));
    }

    // =====================================================================
    //  Texts
    // =====================================================================

    /** The prefix for this zone's messages: its own "prefix", or messages.yml "prefix". */
    public String prefix(String zone) {
        ConfigurationSection section = zoneSection(zone);
        if (section != null && section.isString("prefix")) {
            return section.getString("prefix", "");
        }
        return plugin.messages().prefix();
    }

    /** The raw template of a teleport message ("start", "cooldown", "success") for this zone. */
    public String template(String zone, String which) {
        ConfigurationSection section = zoneSection(zone);
        if (section != null && section.isString("messages." + which)) {
            return section.getString("messages." + which, "");
        }
        return plugin.messages().template("teleport." + which);
    }

    // =====================================================================
    //  Changing (from /czrtp options) - saved right away
    // =====================================================================

    /** Sets a number/switch: for a zone ("zones.<zone>.<key>") or globally (zone == null: "settings.<key>"). */
    public void set(String zone, String key, Object value) {
        if (zone == null) {
            config().set("settings." + key, value);
        } else {
            config().set("zones." + key(zone) + "." + key, value);
        }
        plugin.settingsFile().save();
    }

    /** Sets a teleport message for a zone, or the global one in messages.yml (zone == null). */
    public void setMessage(String zone, String which, String text) {
        if (zone == null) {
            Messages messages = plugin.messages();
            messages.file().get().set("teleport." + which, text);
            messages.file().save();
        } else {
            set(zone, "messages." + which, text);
        }
    }

    /** Sets the prefix of a zone, or the global prefix in messages.yml (zone == null). "" = no prefix. */
    public void setPrefix(String zone, String prefix) {
        if (zone == null) {
            Messages messages = plugin.messages();
            messages.file().get().set("prefix", prefix);
            messages.file().save();
        } else {
            set(zone, "prefix", prefix);
        }
    }

    /** Removes a zone's overrides (when the zone is deleted). */
    public void forget(String zone) {
        config().set("zones." + key(zone), null);
        plugin.settingsFile().save();
    }

    private static String key(String zone) {
        return zone.toLowerCase(Locale.ROOT);
    }
}
