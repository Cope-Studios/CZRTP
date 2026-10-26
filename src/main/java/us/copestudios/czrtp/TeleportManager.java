package us.copestudios.czrtp;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import us.copestudios.copecore.CopeCore;
import us.copestudios.copecore.player.Cooldowns;
import us.copestudios.copecore.player.PlayerStates;
import us.copestudios.copecore.region.Region;
import us.copestudios.copecore.scheduler.Tasks;
import us.copestudios.copecore.text.Text;
import us.copestudios.copecore.util.Locations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The teleport itself: checks, countdown, safe-spot search, cooldowns.
 *
 * <h2>Order of checks for /czrtp tp</h2>
 * <ol>
 *   <li>the zone exists</li>
 *   <li>the player isn't in a blocked CopeCore state (combat, duel, arena... from config.yml)</li>
 *   <li>no other teleport is pending for them</li>
 *   <li>the zone's cooldown is over (skipped with czrtp.bypass.cooldown, and when an admin sends someone)</li>
 * </ol>
 * <p>During the countdown the player has CopeCore's "teleporting" state, so other Cope plugins can see it.</p>
 *
 * <p>FIX from 1.x: "an admin sends a player" was meant to skip the cooldown, but the check compared the player with
 * themselves and was always false. The sender is now passed in explicitly.</p>
 */
public final class TeleportManager implements Listener {

    private final CZRTP plugin;
    /** CopeCore cooldowns, one name per zone ("zone:<name>"). Kept across /czrtp reload. */
    private final Cooldowns cooldowns = new Cooldowns();
    /** player -> their countdown */
    private final Map<UUID, Pending> pending = new HashMap<>();

    private record Pending(String zone, BukkitTask task, Location start) {
    }

    TeleportManager(CZRTP plugin) {
        this.plugin = plugin;
    }

    // =====================================================================
    //  Starting
    // =====================================================================

    /**
     * Starts a teleport.
     *
     * @param player    who is teleported
     * @param zone      zone name
     * @param sentByAdmin true when an admin or the console sends this player (no cooldown is checked or set)
     * @return true if the countdown started
     */
    public boolean teleport(Player player, String zone, boolean sentByAdmin) {
        ZoneSettings settings = plugin.settings();
        Region region = plugin.zones().get(zone);
        if (region == null) {
            plugin.messages().send(player, "teleport.unknown-zone", "zone", zone);
            return false;
        }
        String name = region.name();

        if (!sentByAdmin) {
            String state = blockingState(player, settings.blockedStates());
            if (state != null) {
                plugin.messages().send(player, "teleport.blocked-state", "state", state);
                return false;
            }
        }
        if (pending.containsKey(player.getUniqueId())) {
            plugin.messages().send(player, "teleport.already-pending");
            return false;
        }
        boolean checkCooldown = !sentByAdmin && !player.hasPermission("czrtp.bypass.cooldown");
        if (checkCooldown && cooldowns.isActive(player.getUniqueId(), key(name))) {
            int remaining = cooldowns.remainingSeconds(player.getUniqueId(), key(name));
            zoneMessage(player, name, "cooldown", remaining);
            return false;
        }

        int delay = settings.delay(name);
        if (delay > 0) {
            zoneMessage(player, name, "start", settings.cooldown(name));
        }
        CopeCore.states().set(player, PlayerStates.TELEPORTING, delay * 1000L + 5000L);

        BukkitTask task = Tasks.later(plugin, delay * 20L, () -> finish(player, name, !sentByAdmin));
        pending.put(player.getUniqueId(), new Pending(name, task, player.getLocation().clone()));
        return true;
    }

    /** The first blocked state the player has, or null. */
    private static String blockingState(Player player, List<String> blocked) {
        for (String state : blocked) {
            if (CopeCore.states().has(player, state)) {
                return state;
            }
        }
        return null;
    }

    // =====================================================================
    //  Finishing
    // =====================================================================

    private void finish(Player player, String zone, boolean setCooldown) {
        pending.remove(player.getUniqueId());
        CopeCore.states().remove(player, PlayerStates.TELEPORTING);
        if (!player.isOnline()) {
            return;
        }
        Region region = plugin.zones().get(zone);
        if (region == null) {
            plugin.messages().send(player, "teleport.unknown-zone", "zone", zone);
            return;
        }
        World world = region.bukkitWorld();
        if (world == null) {
            plugin.messages().send(player, "teleport.world-missing", "zone", zone);
            return;
        }
        Location target = randomSafeSpot(region, world);
        if (target == null) {
            plugin.messages().send(player, "teleport.no-safe-spot", "zone", zone);
            return;
        }
        target.setYaw(player.getLocation().getYaw());
        target.setPitch(player.getLocation().getPitch());
        player.setFallDistance(0);
        player.teleport(target);
        zoneMessage(player, zone, "success", plugin.settings().cooldown(zone));
        if (setCooldown) {
            cooldowns.set(player.getUniqueId(), key(zone), plugin.settings().cooldown(zone) * 1000L);
        }
    }

    /**
     * A random safe spot in the zone's area: random x/z inside it, then the highest safe ground there
     * (CopeCore's finder: solid ground, two free blocks, no lava/fire/cactus, stays under the Nether roof).
     * After "search-attempts" misses, the middle of the zone is tried.
     *
     * <p>FIX from 1.x: negative coordinates were rounded the wrong way ((int) -3.7 = -3) and players were put on the
     * corner of a block instead of its middle.</p>
     */
    private Location randomSafeSpot(Region region, World world) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int bottom = world.getMinHeight();
        int top = world.getMaxHeight();
        for (int attempt = 0; attempt < plugin.settings().searchAttempts(); attempt++) {
            int x = random.nextInt(region.minX(), region.maxX() + 1);
            int z = random.nextInt(region.minZ(), region.maxZ() + 1);
            Location spot = Locations.findSafeSpot(world, x, z, bottom, top);
            if (spot != null) {
                return spot;
            }
        }
        int centerX = Math.floorDiv(region.minX() + region.maxX(), 2);
        int centerZ = Math.floorDiv(region.minZ() + region.maxZ(), 2);
        return Locations.findSafeSpot(world, centerX, centerZ, bottom, top);
    }

    // =====================================================================
    //  Cancelling
    // =====================================================================

    /** /czrtp cancel. Returns false if nothing was pending. */
    public boolean cancel(Player player) {
        Pending waiting = pending.remove(player.getUniqueId());
        if (waiting == null) {
            return false;
        }
        waiting.task().cancel();
        CopeCore.states().remove(player, PlayerStates.TELEPORTING);
        return true;
    }

    /** Plugin disable: stop every countdown. */
    void cancelAll() {
        for (Pending waiting : pending.values()) {
            waiting.task().cancel();
        }
        pending.clear();
    }

    /** config.yml "settings.cancel-on-move": walking a block away cancels the countdown. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending waiting = pending.get(event.getPlayer().getUniqueId());
        if (waiting == null || !plugin.settings().cancelOnMove() || event.getTo() == null) {
            return;
        }
        Location from = waiting.start();
        Location to = event.getTo();
        boolean moved = from.getWorld() != to.getWorld()
                || from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ()
                || Math.abs(from.getY() - to.getY()) > 1.0;
        if (moved && cancel(event.getPlayer())) {
            plugin.messages().send(event.getPlayer(), "teleport.moved");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer());
        plugin.zones().forget(event.getPlayer());
    }

    // =====================================================================
    //  Cooldown info (placeholders)
    // =====================================================================

    /** Seconds left on this zone's cooldown for the player (0 = ready). */
    public int remainingCooldown(UUID player, String zone) {
        return cooldowns.remainingSeconds(player, key(zone));
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /**
     * Sends one of a zone's teleport messages ("start", "cooldown", "success"). These carry their own
     * {@code <prefix>} (the zone's prefix), so they're sent without the automatic one.
     */
    private void zoneMessage(Player player, String zone, String which, int cooldownSeconds) {
        ZoneSettings settings = plugin.settings();
        String text = Text.replace(settings.template(zone, which),
                "prefix", settings.prefix(zone),
                "zone", zone,
                "zone_name", zone, // CZRTP 1.x placeholder
                "delay", String.valueOf(settings.delay(zone)),
                "cooldown", String.valueOf(cooldownSeconds),
                "player", player.getName());
        Text.send(player, text);
    }

    private static String key(String zone) {
        return "zone:" + zone.toLowerCase(java.util.Locale.ROOT);
    }
}
