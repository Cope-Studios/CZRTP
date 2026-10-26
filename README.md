# CZRTP (Cope Zone Random Teleport)

Draw a zone, and players are teleported to a random safe spot inside it, with a countdown, cooldowns and per-zone
messages. By Cope Studios.

- **Minecraft:** 1.20.1 and newer (tested against the 1.20 and 1.21.11 APIs)
- **Servers:** Spigot, Paper, Purpur, Arclight
- **Java:** 17 or newer (1.20.5+ servers need Java 21 anyway)
- **Needs:** [CopeCore](../CopeCore) 1.1.0 or newer
- **Optional:** PlaceholderAPI

---

## Install

1. Put `CopeCore-<version>.jar` **and** `CZRTP-2.0.0.jar` in `plugins/`.
2. Start the server. CZRTP creates `plugins/CZRTP/` with `config.yml`, `messages.yml` and `zones.yml`.
3. Make a zone (below), give players `czrtp.use` + `czrtp.<zone>`, and they can `/czrtp tp <zone>`.

## Upgrading from 1.x

Drop in the new jar (and CopeCore). On the first start, nothing is lost:

- every zone in the old `zones.yml` is imported (the box around its four corners, the same area 1.x used);
- `config_zone.yml`: the delay and cooldown go to `config.yml`, your customized messages and prefix go to
  `messages.yml`, per-zone settings go to `config.yml` under `zones:`;
- the old files are renamed to `zones.yml.old`, `config_zone.yml.old` and `config.yml.old` (1.x's config.yml was an
  unused copy of the zone settings).

Old texts keep working: `&a`, `&x&R&R&G&G&B&B`, `{prefix}`, `{zone_name}`, `{delay}`, `{cooldown}`.

## Making a zone

```
/czrtp create spawn           start the zone
/czrtp spawn pos1             stand at one corner
/czrtp spawn pos2             stand at the opposite corner -> saved
```

Or select two corners with the CopeCore wand (`/copecore wand`) first, then `/czrtp create spawn` makes the zone
right away. Using `pos1` / `pos2` on an existing zone moves that corner. Only the area (x/z) matters: players land on
the highest safe ground inside it. (1.x asked for four corners; two opposite corners make the same box.)

## Commands

`/czrtp help` shows a clickable list of what you're allowed to use.

| Command | Permission | What it does |
|---|---|---|
| `/czrtp tp <zone>` | `czrtp.use` + zone permission | Teleport to a random spot in the zone |
| `/czrtp tp <zone> <player>` | `czrtp.admin` (or console) | Send a player (no cooldown) |
| `/czrtp cancel` | `czrtp.use` | Cancel your pending teleport |
| `/czrtp list` | `czrtp.use` | List zones (click one to type the tp command) |
| `/czrtp create <zone>` | `czrtp.admin` | Start a zone |
| `/czrtp <zone> pos1\|pos2` | `czrtp.admin` | Set a corner |
| `/czrtp delete <zone>` | `czrtp.admin` | Delete a zone and its settings |
| `/czrtp options <zone\|global> <option> <value>` | `czrtp.admin` | Change settings (below) |
| `/czrtp reload` | `czrtp.admin` | Reload config.yml, messages.yml and zones.yml |

Options: `delay`, `cooldown` (seconds), `message`, `cooldown-message`, `success-message` (text),
`prefix-enabled` (true/false), `prefix-text` (text). `global` changes the defaults.

| Permission | Default | Meaning |
|---|---|---|
| `czrtp.use` | op | Use `/czrtp tp`, `cancel`, `list` |
| `czrtp.<zone>` | - | Allowed to teleport to that zone (also `czrtp.*` for every zone) |
| `czrtp.admin` | op | Everything, every zone, no cooldowns |
| `czrtp.bypass.cooldown` | false | Never waits for a cooldown |

## Files

| File | What's in it |
|---|---|
| `config.yml` | Delay, cooldown, cancel-on-move, blocked states; per-zone overrides under `zones:` |
| `messages.yml` | Every text; `teleport.start/cooldown/success` are the teleport messages (put `<prefix>` where you want it) |
| `zones.yml` | The zones (CopeCore regions - other Cope plugins can see them) |

### Teleport messages per zone

```yaml
# config.yml
zones:
  spawn:
    teleport-delay: 5
    cooldown: 120
    prefix: "<aqua>[Spawn] "     # "" = no prefix
    messages:
      start: "<prefix><green>Teleporting to <aqua><zone></aqua> in <aqua><delay></aqua>s..."
      cooldown: "<prefix><red>Wait <gold><cooldown></gold>s."
      success: "<prefix><aqua>Welcome to <zone>!"
```

Placeholders: `<zone>`, `<delay>`, `<cooldown>`, `<prefix>`, `<player>` (also `{zone_name}` like 1.x).

### Shared player states

Players with a CopeCore state from `settings.blocked-states` can't start a teleport (default: `combat`, `in-duel`,
`in-arena` - no escaping a fight). During the countdown the player has the `teleporting` state, which other Cope plugins
can see.

## Placeholders (PlaceholderAPI)

| Placeholder | Value |
|---|---|
| `%cope_czrtp_cooldown_<zone>%` | Seconds left before the player can use that zone again (0 = ready) |
| `%cope_czrtp_ready_<zone>%` | `true` / `false` |
| `%cope_czrtp_zones%` | Number of zones |

## Building

```
# 1) CopeCore first (mvn clean install in the CopeCore folder)
# 2) Check copecore.version in pom.xml matches CopeCore's version
mvn clean package
```

The jar is `target/CZRTP-2.0.0.jar`.

```
src/main/java/us/copestudios/czrtp/
  CZRTP.java            main class
  Zones.java            zones (CopeCore regions) and corner setup
  ZoneSettings.java     delay / cooldown / prefix / messages, global and per zone
  TeleportManager.java  countdown, cooldowns, safe-spot search, teleport
  CzrtpCommand.java     /czrtp
  Placeholders.java     %cope_czrtp_...%
  LegacyMigration.java  upgrade from 1.x
```

## Changelog

### 2.0.0
- Now built on **CopeCore** (texts, commands, regions, cooldowns, safe spots, placeholders). Maven instead of Gradle.
- Works on **1.20.1+** on Spigot, Paper, Purpur and Arclight (was Purpur 1.21.4 only). Java 17.
- Package renamed from `com.example.czrtp` to `us.copestudios.czrtp`.
- Every text in `messages.yml` (MiniMessage + old codes); automatic import of 1.x zones and settings.
- New: `/czrtp list`, `/czrtp delete`, CopeCore wand zones, `cancel-on-move`, `blocked-states`,
  `czrtp.bypass.cooldown`, PlaceholderAPI placeholders.
- Zones need two corners instead of four.
- Setting up zones now needs `czrtp.admin` (1.x allowed anyone with `czrtp.use`).
- Fix: admins sending a player still applied the cooldown (the check compared the player with themselves).
- Fix: tab completion never worked (it was never registered).
- Fix: wrong spot on negative coordinates and teleporting onto a block corner; Nether roof teleports.
- Fix: `Position.java` and `SupportingClasses.java` defined the same classes twice.
- Fix: the shipped `config_zone.yml` and `config.yml` disagreed (only one was used).
- Cooldowns now survive `/czrtp reload`.
