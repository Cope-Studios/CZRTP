package us.copestudios.czrtp;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import us.copestudios.copecore.command.CommandContext;
import us.copestudios.copecore.command.CommandFailure;
import us.copestudios.copecore.command.CopeCommand;
import us.copestudios.copecore.region.Region;
import us.copestudios.copecore.text.Messages;
import us.copestudios.copecore.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /czrtp - built with CopeCore's {@link CopeCommand} (permissions, usage lines, clickable help and tab completion).
 *
 * <pre>
 * /czrtp tp &lt;zone&gt; [player]           teleport (yourself, or send a player: admin / console)
 * /czrtp cancel                         cancel your pending teleport
 * /czrtp create &lt;zone&gt;                start a zone (or make it from your CopeCore wand selection)
 * /czrtp &lt;zone&gt; pos1|pos2               set a corner where you stand
 * /czrtp delete &lt;zone&gt;  /czrtp list
 * /czrtp options &lt;zone|global&gt; &lt;option&gt; &lt;value...&gt;
 * /czrtp reload
 * </pre>
 *
 * <p>Changes from 1.x: setting up zones now needs czrtp.admin (it only needed czrtp.use before), and the console can
 * use every admin command, not just "tp &lt;zone&gt; &lt;player&gt;". Tab completion now actually works (1.x never
 * registered it).</p>
 */
public final class CzrtpCommand {

    private static final String USE = "czrtp.use";
    private static final String ADMIN = "czrtp.admin";
    private static final List<String> OPTIONS = List.of("delay", "cooldown", "message", "cooldown-message",
            "success-message", "prefix-enabled", "prefix-text");

    private final CZRTP plugin;
    private final Messages messages;

    CzrtpCommand(CZRTP plugin) {
        this.plugin = plugin;
        this.messages = plugin.messages();
    }

    void register() {
        CopeCommand command = new CopeCommand(plugin, "czrtp", messages);

        command.sub("tp").permission(USE).usage("<zone> [player]").minArgs(1)
                .descriptionKey("command.help.tp").description("teleport to a random spot in a zone")
                .tab(ctx -> ctx.size() <= 1 ? allowedZones(ctx.sender()) : onlineNames())
                .executes(this::tp);

        command.sub("cancel").permission(USE).playerOnly()
                .descriptionKey("command.help.cancel").description("cancel your pending teleport")
                .executes(ctx -> ctx.reply(plugin.teleports().cancel(ctx.player())
                        ? "teleport.cancelled" : "teleport.nothing-to-cancel"));

        command.sub("create").permission(ADMIN).playerOnly().usage("<zone>").minArgs(1)
                .descriptionKey("command.help.create").description("start a new zone")
                .executes(this::create);

        // "/czrtp <zone> pos1" - the first word is a zone name, not a sub command.
        command.fallback().permission(ADMIN).playerOnly().usage("<zone> <pos1|pos2>").minArgs(2)
                .descriptionKey("command.help.corner").description("set a corner of a zone")
                .tab(ctx -> ctx.size() <= 1 ? plugin.zones().names() : List.of("pos1", "pos2"))
                .executes(this::corner);

        command.sub("delete").aliases("remove").permission(ADMIN).usage("<zone>").minArgs(1)
                .descriptionKey("command.help.delete").description("delete a zone")
                .tab(ctx -> plugin.zones().names())
                .executes(ctx -> {
                    String zone = ctx.arg(0).toLowerCase(Locale.ROOT);
                    if (!plugin.zones().delete(zone)) {
                        throw new CommandFailure("teleport.unknown-zone", "zone", Text.escape(zone));
                    }
                    plugin.settings().forget(zone);
                    ctx.reply("zone.deleted", "zone", zone);
                });

        command.sub("list").permission(USE)
                .descriptionKey("command.help.list").description("list zones")
                .executes(this::list);

        command.sub("options").permission(ADMIN).usage("<zone|global> <option> <value>").minArgs(3)
                .descriptionKey("command.help.options").description("change delay, cooldown, messages or prefix")
                .tab(ctx -> {
                    if (ctx.size() <= 1) {
                        List<String> targets = new ArrayList<>(plugin.zones().names());
                        targets.add(0, "global");
                        return targets;
                    }
                    if (ctx.size() == 2) {
                        return OPTIONS;
                    }
                    String option = ctx.arg(1).toLowerCase(Locale.ROOT);
                    return option.equals("prefix-enabled") ? List.of("true", "false") : List.of();
                })
                .executes(this::options);

        command.sub("reload").permission(ADMIN)
                .descriptionKey("command.help.reload").description("reload the files")
                .executes(ctx -> {
                    long started = System.currentTimeMillis();
                    plugin.reload();
                    ctx.reply("generic.reloaded", "plugin", plugin.getName(),
                            "ms", String.valueOf(System.currentTimeMillis() - started));
                });

        command.register();
    }

    // =====================================================================
    //  Teleporting
    // =====================================================================

    private void tp(CommandContext ctx) {
        String zone = ctx.arg(0);
        if (!plugin.zones().exists(zone)) {
            throw new CommandFailure("teleport.unknown-zone", "zone", Text.escape(zone));
        }
        String name = plugin.zones().get(zone).name();

        // Send someone else: admins and the console.
        if (ctx.has(1)) {
            if (!ctx.sender().hasPermission(ADMIN)) {
                throw new CommandFailure("generic.no-permission");
            }
            Player target = ctx.onlinePlayer(1);
            if (plugin.teleports().teleport(target, name, true)) {
                ctx.reply("teleport.sent-other", "player", target.getName(), "zone", name);
            }
            return;
        }

        if (!ctx.isPlayer()) {
            throw new CommandFailure("generic.players-only");
        }
        Player player = ctx.player();
        if (!mayUse(player, name)) {
            throw new CommandFailure("teleport.no-permission", "zone", name);
        }
        plugin.teleports().teleport(player, name, false);
    }

    /** czrtp.admin, czrtp.* or czrtp.&lt;zone&gt; (same rule as 1.x). */
    private static boolean mayUse(CommandSender sender, String zone) {
        return sender.hasPermission(ADMIN) || sender.hasPermission("czrtp.*")
                || sender.hasPermission("czrtp." + zone.toLowerCase(Locale.ROOT));
    }

    // =====================================================================
    //  Zone setup
    // =====================================================================

    private void create(CommandContext ctx) {
        String name = ctx.arg(0).toLowerCase(Locale.ROOT);
        if (!Zones.validName(name)) {
            throw new CommandFailure("zone.invalid-name");
        }
        if (plugin.zones().exists(name)) {
            throw new CommandFailure("zone.exists", "zone", name);
        }
        if (plugin.zones().start(ctx.player(), name)) {
            ctx.reply("zone.created-from-wand", "zone", name);
        } else {
            ctx.reply("zone.started", "zone", name);
        }
    }

    private void corner(CommandContext ctx) {
        String name = ctx.arg(0).toLowerCase(Locale.ROOT);
        String which = ctx.arg(1).toLowerCase(Locale.ROOT);
        int corner;
        switch (which) {
            case "pos1" -> corner = 1;
            case "pos2" -> corner = 2;
            case "pos3", "pos4" -> throw new CommandFailure("zone.only-two-corners");
            default -> throw new CommandFailure("generic.unknown-subcommand", "input", ctx.arg(0), "label", ctx.label());
        }
        Player player = ctx.player();
        switch (plugin.zones().setCorner(player, name, corner)) {
            case SET -> ctx.reply("zone.corner-set", "corner", String.valueOf(corner), "zone", name);
            case CREATED -> {
                ctx.reply("zone.corner-set", "corner", String.valueOf(corner), "zone", name);
                ctx.reply("zone.created", "zone", name);
            }
            case UPDATED -> ctx.reply("zone.updated", "zone", name);
            case NOT_STARTED -> ctx.reply("zone.not-started", "zone", name);
            case OTHER_WORLD -> {
                Region existing = plugin.zones().get(name);
                String world = existing != null ? existing.world() : String.valueOf(plugin.zones().pendingWorld(player));
                ctx.reply("zone.same-world", "world", world);
            }
        }
    }

    private void list(CommandContext ctx) {
        if (plugin.zones().names().isEmpty()) {
            ctx.reply("zone.none");
            return;
        }
        messages.sendRaw(ctx.sender(), "zone.list-header", "count", String.valueOf(plugin.zones().names().size()));
        for (Region region : plugin.zones().all()) {
            messages.sendRaw(ctx.sender(), "zone.list-line", "zone", region.name(), "world", region.world(),
                    "x", String.valueOf(region.sizeX()), "z", String.valueOf(region.sizeZ()));
        }
    }

    // =====================================================================
    //  /czrtp options <zone|global> <option> <value...>
    // =====================================================================

    private void options(CommandContext ctx) {
        String targetArg = ctx.arg(0);
        boolean global = targetArg.equalsIgnoreCase("global");
        if (!global && !plugin.zones().exists(targetArg)) {
            throw new CommandFailure("teleport.unknown-zone", "zone", Text.escape(targetArg));
        }
        String zone = global ? null : plugin.zones().get(targetArg).name();
        String target = global ? messages.format("options.global-target") : messages.format("options.zone-target", "zone", zone);
        String option = ctx.arg(1).toLowerCase(Locale.ROOT);
        String value = ctx.joined(2);
        ZoneSettings settings = plugin.settings();

        switch (option) {
            case "delay" -> {
                int seconds = wholeNumber(value);
                settings.set(zone, "teleport-delay", seconds);
                reportSet(ctx, option, target, String.valueOf(seconds));
            }
            case "cooldown" -> {
                int seconds = wholeNumber(value);
                settings.set(zone, "cooldown", seconds);
                reportSet(ctx, option, target, String.valueOf(seconds));
            }
            case "message", "cooldown-message", "success-message" -> {
                String which = switch (option) {
                    case "message" -> "start";
                    case "cooldown-message" -> "cooldown";
                    default -> "success";
                };
                settings.setMessage(zone, which, value);
                reportSet(ctx, option, target, preview(zone, value));
            }
            case "prefix-enabled" -> {
                boolean enabled;
                if (value.equalsIgnoreCase("true")) {
                    enabled = true;
                } else if (value.equalsIgnoreCase("false")) {
                    enabled = false;
                } else {
                    throw new CommandFailure("options.bad-bool");
                }
                // Off = an empty prefix. On (for a zone) = back to the global prefix.
                if (!enabled) {
                    settings.setPrefix(zone, "");
                } else if (zone != null) {
                    settings.set(zone, "prefix", null);
                } else if (messages.prefix().isEmpty()) {
                    settings.setPrefix(null, "<dark_gray>[<gray>CZRTP<dark_gray>] ");
                }
                reportSet(ctx, option, target, String.valueOf(enabled));
            }
            case "prefix-text" -> {
                String prefix = value.endsWith(" ") ? value : value + " ";
                settings.setPrefix(zone, prefix);
                reportSet(ctx, option, target, prefix);
            }
            default -> {
                ctx.reply("options.unknown", "option", Text.escape(option));
                ctx.reply("options.available");
            }
        }
    }

    private void reportSet(CommandContext ctx, String option, String target, String value) {
        ctx.reply("options.set", "option", option, "target", target, "value", value);
    }

    /** How a message will look (placeholders filled with example values). */
    private String preview(String zone, String template) {
        String name = zone == null ? "example" : zone;
        return Text.replace(template, "prefix", plugin.settings().prefix(zone), "zone", name, "zone_name", name,
                "delay", String.valueOf(plugin.settings().delay(zone)),
                "cooldown", String.valueOf(plugin.settings().cooldown(zone)), "player", "Steve");
    }

    private static int wholeNumber(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value < 0) {
                throw new CommandFailure("options.bad-number");
            }
            return value;
        } catch (NumberFormatException notANumber) {
            throw new CommandFailure("options.bad-number");
        }
    }

    // =====================================================================
    //  Tab helpers
    // =====================================================================

    private List<String> allowedZones(CommandSender sender) {
        List<String> zones = new ArrayList<>();
        for (String zone : plugin.zones().names()) {
            if (mayUse(sender, zone)) {
                zones.add(zone);
            }
        }
        return zones;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return names;
    }
}
