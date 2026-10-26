package us.copestudios.czrtp;

import org.bukkit.OfflinePlayer;
import us.copestudios.copecore.CopeCore;

import java.util.Locale;

/**
 * PlaceholderAPI placeholders, registered through CopeCore (only active when PlaceholderAPI is installed).
 *
 * <pre>
 * %cope_czrtp_cooldown_&lt;zone&gt;%   seconds left before the player can use that zone again (0 = ready)
 * %cope_czrtp_ready_&lt;zone&gt;%      true / false
 * %cope_czrtp_zones%              number of zones
 * </pre>
 */
final class Placeholders {

    private Placeholders() {
    }

    static void register(CZRTP plugin) {
        CopeCore.placeholders().register(plugin, "czrtp", (player, key) -> resolve(plugin, player, key));
    }

    private static String resolve(CZRTP plugin, OfflinePlayer player, String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.equals("zones")) {
            return String.valueOf(plugin.zones().names().size());
        }
        if (player == null) {
            return null;
        }
        if (lower.startsWith("cooldown_")) {
            return String.valueOf(plugin.teleports().remainingCooldown(player.getUniqueId(), lower.substring(9)));
        }
        if (lower.startsWith("ready_")) {
            return String.valueOf(plugin.teleports().remainingCooldown(player.getUniqueId(), lower.substring(6)) == 0);
        }
        return null;
    }
}
