package us.copestudios.czrtp;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import us.copestudios.copecore.region.Region;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One-time upgrade from CZRTP 1.x. Nothing is deleted: old files are renamed to "*.old".
 *
 * <h2>Step 1 - {@link #prepareFiles()} (before the new files are opened)</h2>
 * <ul>
 *   <li><b>zones.yml</b>: 1.x stored zones as "zones: name: pos1..pos4". The new zones.yml is a CopeCore region file,
 *       so the old one is renamed to zones.yml.old (and imported in step 2).</li>
 *   <li><b>config.yml</b>: in 1.x it was an unused copy of the zone settings ("global:"). It's renamed to
 *       config.yml.old so a clean new config.yml is written.</li>
 * </ul>
 *
 * <h2>Step 2 - {@link #importOldData()} (after the new files exist)</h2>
 * <ul>
 *   <li>Every old zone becomes a region: the box around its four corners (1.x teleported inside that box too).</li>
 *   <li><b>config_zone.yml</b>: global delay/cooldown go to config.yml "settings"; customized global messages and the
 *       prefix go to messages.yml; per-zone settings go to config.yml "zones". Then it's renamed to
 *       config_zone.yml.old.</li>
 * </ul>
 */
final class LegacyMigration {

    private final CZRTP plugin;
    private File oldZones;

    LegacyMigration(CZRTP plugin) {
        this.plugin = plugin;
    }

    // =====================================================================
    //  Step 1
    // =====================================================================

    void prepareFiles() {
        File folder = plugin.getDataFolder();

        File zones = new File(folder, "zones.yml");
        if (zones.isFile()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(zones);
            if (yaml.isConfigurationSection("zones") || zones.length() == 0) {
                File renamed = new File(folder, "zones.yml.old");
                if (zones.renameTo(renamed)) {
                    oldZones = renamed;
                }
            }
        }

        File config = new File(folder, "config.yml");
        if (config.isFile() && YamlConfiguration.loadConfiguration(config).isConfigurationSection("global")) {
            if (!config.renameTo(new File(folder, "config.yml.old"))) {
                plugin.getLogger().warning("Could not rename the old config.yml - delete it so a new one is made.");
            }
        }
    }

    // =====================================================================
    //  Step 2
    // =====================================================================

    void importOldData() {
        int zones = importZones();
        boolean settings = importZoneConfig();
        if (zones > 0 || settings) {
            plugin.getLogger().info("Upgraded from CZRTP 1.x: " + zones + " zone(s) imported"
                    + (settings ? ", settings and texts from config_zone.yml imported" : "")
                    + ". The old files were renamed to *.old.");
        }
    }

    private int importZones() {
        if (oldZones == null) {
            return 0;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(oldZones);
        ConfigurationSection section = yaml.getConfigurationSection("zones");
        if (section == null) {
            return 0;
        }
        int imported = 0;
        for (String name : section.getKeys(false)) {
            Region region = boxAround(name, section.getConfigurationSection(name));
            if (region == null) {
                plugin.getLogger().warning("Zone '" + name + "' in the old zones.yml was incomplete and was skipped.");
                continue;
            }
            plugin.zones().put(region);
            imported++;
        }
        return imported;
    }

    /** The box around pos1..pos4 of an old zone, or null if it has fewer than two corners. */
    private static Region boxAround(String name, ConfigurationSection zone) {
        if (zone == null) {
            return null;
        }
        String world = null;
        List<double[]> points = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ConfigurationSection pos = zone.getConfigurationSection("pos" + i);
            if (pos == null) {
                continue;
            }
            if (world == null) {
                world = pos.getString("world");
            }
            points.add(new double[]{pos.getDouble("x"), pos.getDouble("y"), pos.getDouble("z")});
        }
        if (world == null || points.size() < 2) {
            return null;
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : points) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
        }
        return new Region(name.toLowerCase(Locale.ROOT), world,
                (int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
                (int) Math.floor(maxX), (int) Math.floor(maxY), (int) Math.floor(maxZ));
    }

    private boolean importZoneConfig() {
        File file = new File(plugin.getDataFolder(), "config_zone.yml");
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadOldDefaults();
        ZoneSettings settings = plugin.settings();

        // ---- global ----
        if (old.contains("global.teleport_delay")) {
            settings.set(null, "teleport-delay", old.getInt("global.teleport_delay"));
        }
        if (old.contains("global.cooldown")) {
            settings.set(null, "cooldown", old.getInt("global.cooldown"));
        }
        String[][] texts = {{"message", "start"}, {"cooldown_message", "cooldown"}, {"success_message", "success"}};
        for (String[] pair : texts) {
            String value = old.getString("global." + pair[0]);
            if (value != null && customized(defaults, pair[0], value)) {
                settings.setMessage(null, pair[1], value);
            }
        }
        if (old.contains("global.prefix.enabled") && !old.getBoolean("global.prefix.enabled")) {
            settings.setPrefix(null, "");
        } else {
            String prefix = old.getString("global.prefix.text");
            if (prefix != null && customized(defaults, "prefix", prefix)) {
                settings.setPrefix(null, prefix + " ");
            }
        }

        // ---- per zone (only what differs from the old global values) ----
        ConfigurationSection zones = old.getConfigurationSection("zones");
        if (zones != null) {
            for (String name : zones.getKeys(false)) {
                ConfigurationSection zone = zones.getConfigurationSection(name);
                if (zone == null) {
                    continue;
                }
                String key = name.toLowerCase(Locale.ROOT);
                copyNumber(old, zone, "teleport_delay", key, "teleport-delay", settings);
                copyNumber(old, zone, "cooldown", key, "cooldown", settings);
                for (String[] pair : texts) {
                    String value = zone.getString(pair[0]);
                    if (value != null && !value.equals(old.getString("global." + pair[0]))) {
                        settings.setMessage(key, pair[1], value);
                    }
                }
                if (zone.isConfigurationSection("prefix")) {
                    boolean enabled = zone.getBoolean("prefix.enabled", true);
                    String text = zone.getString("prefix.text", "");
                    if (!enabled) {
                        settings.setPrefix(key, "");
                    } else if (!text.equals(old.getString("global.prefix.text"))) {
                        settings.setPrefix(key, text + " ");
                    }
                }
            }
        }

        if (!file.renameTo(new File(plugin.getDataFolder(), "config_zone.yml.old"))) {
            plugin.getLogger().warning("Imported config_zone.yml but could not rename it; delete it by hand.");
        }
        return true;
    }

    private static void copyNumber(YamlConfiguration old, ConfigurationSection zone, String oldKey,
                                   String zoneName, String newKey, ZoneSettings settings) {
        if (zone.contains(oldKey) && zone.getInt(oldKey) != old.getInt("global." + oldKey, Integer.MIN_VALUE)) {
            settings.set(zoneName, newKey, zone.getInt(oldKey));
        }
    }

    private static boolean customized(YamlConfiguration defaults, String key, String value) {
        if (defaults == null) {
            return true;
        }
        for (String original : defaults.getStringList(key)) {
            if (Objects.equals(original, value)) {
                return false;
            }
        }
        return true;
    }

    private YamlConfiguration loadOldDefaults() {
        try (InputStream in = plugin.getResource("legacy/old-defaults.yml")) {
            return in == null ? null : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception error) {
            return null;
        }
    }
}
