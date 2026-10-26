package us.copestudios.czrtp;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import us.copestudios.copecore.CopeCore;
import us.copestudios.copecore.region.Region;
import us.copestudios.copecore.region.RegionStore;
import us.copestudios.copecore.region.Selection;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The teleport zones, stored as CopeCore regions in plugins/CZRTP/zones.yml.
 *
 * <h2>Making a zone</h2>
 * <ul>
 *   <li>{@code /czrtp create <name>}, then {@code /czrtp <name> pos1} at one corner and {@code pos2} at the opposite
 *       corner. The zone is saved as soon as both are set.</li>
 *   <li>Or select two corners with the CopeCore wand ({@code /copecore wand}) first: {@code /czrtp create <name>}
 *       then makes the zone right away.</li>
 *   <li>{@code /czrtp <name> pos1} on an existing zone moves that corner.</li>
 * </ul>
 *
 * <p>CZRTP 1.x used four corners and the box around them; two opposite corners give the same box. The height of the
 * zone doesn't matter for teleporting: players land on the highest safe ground inside the zone's area.</p>
 */
public final class Zones {

    /** A zone being set up by a player (not saved yet). */
    private static final class Pending {
        final String name;
        Location corner1;
        Location corner2;

        Pending(String name) {
            this.name = name;
        }
    }

    private final CZRTP plugin;
    private final RegionStore store;
    /** player -> the zone they're setting up */
    private final Map<UUID, Pending> pending = new HashMap<>();

    Zones(CZRTP plugin) {
        this.plugin = plugin;
        this.store = CopeCore.regions().store(plugin, "zones.yml");
    }

    void reload() {
        store.reload();
    }

    /** Letters, numbers, - and _ (max 32), and not a sub command word. */
    public static boolean validName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,32}")
                && !List.of("create", "delete", "list", "tp", "cancel", "options", "reload", "help", "global")
                .contains(name.toLowerCase(Locale.ROOT));
    }

    // =====================================================================
    //  Reading
    // =====================================================================

    public Region get(String name) {
        return name == null ? null : store.get(name);
    }

    public boolean exists(String name) {
        return name != null && store.contains(name);
    }

    public List<String> names() {
        return store.names();
    }

    public java.util.Collection<Region> all() {
        return store.all();
    }

    // =====================================================================
    //  Setting up
    // =====================================================================

    /**
     * Starts a zone for this player. If they have a complete CopeCore wand selection, the zone is made from it at
     * once.
     *
     * @return true if the zone was created immediately from the wand selection
     */
    public boolean start(Player player, String name) {
        Selection selection = CopeCore.regions().selection(player);
        if (selection.isComplete() && !selection.isDifferentWorlds()) {
            Region region = selection.toRegion(name);
            store.put(region);
            CopeCore.regions().showOutline(player, region);
            pending.remove(player.getUniqueId());
            return true;
        }
        pending.put(player.getUniqueId(), new Pending(name.toLowerCase(Locale.ROOT)));
        return false;
    }

    /** What happened when a corner was set. */
    public enum CornerResult { SET, CREATED, UPDATED, NOT_STARTED, OTHER_WORLD }

    /**
     * Sets corner 1 or 2 of a zone to where the player stands.
     * <ul>
     *   <li>Zone being set up by this player: remembers the corner; with both set the zone is saved (CREATED).</li>
     *   <li>Existing zone: moves that corner (UPDATED).</li>
     * </ul>
     */
    public CornerResult setCorner(Player player, String name, int corner) {
        String key = name.toLowerCase(Locale.ROOT);
        Location here = player.getLocation();

        Region existing = store.get(key);
        if (existing != null) {
            if (here.getWorld() == null || !here.getWorld().getName().equals(existing.world())) {
                return CornerResult.OTHER_WORLD;
            }
            // Keep the opposite corner of the current box, replace this one.
            boolean first = corner == 1;
            int ox = first ? existing.maxX() : existing.minX();
            int oy = first ? existing.maxY() : existing.minY();
            int oz = first ? existing.maxZ() : existing.minZ();
            Region moved = new Region(key, existing.world(), here.getBlockX(), here.getBlockY(), here.getBlockZ(), ox, oy, oz);
            store.put(moved);
            CopeCore.regions().showOutline(player, moved);
            return CornerResult.UPDATED;
        }

        Pending setup = pending.get(player.getUniqueId());
        if (setup == null || !setup.name.equals(key)) {
            return CornerResult.NOT_STARTED;
        }
        if (corner == 1) {
            setup.corner1 = here.clone();
        } else {
            setup.corner2 = here.clone();
        }
        if (setup.corner1 == null || setup.corner2 == null) {
            return CornerResult.SET;
        }
        if (setup.corner1.getWorld() == null || !setup.corner1.getWorld().equals(setup.corner2.getWorld())) {
            return CornerResult.OTHER_WORLD;
        }
        Region region = Region.of(key, setup.corner1, setup.corner2);
        store.put(region);
        pending.remove(player.getUniqueId());
        CopeCore.regions().showOutline(player, region);
        return CornerResult.CREATED;
    }

    /** The world of the corner already set during setup (for the "same world" message), or null. */
    public String pendingWorld(Player player) {
        Pending setup = pending.get(player.getUniqueId());
        if (setup == null) {
            return null;
        }
        Location set = setup.corner1 != null ? setup.corner1 : setup.corner2;
        return set == null || set.getWorld() == null ? null : set.getWorld().getName();
    }

    public boolean delete(String name) {
        pending.values().removeIf(setup -> setup.name.equalsIgnoreCase(name));
        return store.remove(name);
    }

    /** Adds a finished zone (used by the upgrade from 1.x). */
    void put(Region region) {
        store.put(region);
    }

    void forget(Player player) {
        pending.remove(player.getUniqueId());
    }
}
