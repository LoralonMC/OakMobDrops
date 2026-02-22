# OakMobDrops

Advanced custom mob drop system with multi-backend support, announcements, and comprehensive statistics.

## Features

- **Multi-backend support** for ExecutableItems, ItemsAdder, Nexo, and vanilla Minecraft items
- **Multiple independent drops per mob**, each with configurable drop rates and amount ranges
- **Looting enchantment support** with configurable multipliers
- **Spawner tracking** to control drops from spawner-spawned mobs, with slime split inheritance
- **Announcement system** using MiniMessage format with item hover tooltips
- **Command execution** on drop success with placeholder support (DiscordSRV, economy, etc.)
- **Comprehensive statistics** with per-player breakdowns, rarest drops, expected vs actual rates, and JSON or SQLite storage backends
- **PlaceholderAPI integration** with 6 placeholders for scoreboards and other plugins
- **Config-driven messages** — all player-facing text is customizable via config.yml

## Requirements

- **Server**: Paper 1.21.10+
- **Java**: 21+
- **Optional**: ExecutableItems, ItemsAdder, Nexo, PlaceholderAPI (auto-detected)

## Installation

1. Download the latest release from the [Releases](../../releases) page
2. Place the JAR in your server's `plugins/` folder
3. Restart the server
4. Edit `plugins/OakMobDrops/config.yml` to configure mob drops
5. Run `/omd reload` to apply changes

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/omd` | Show help | `oakmobdrops.use` |
| `/omd reload` | Reload configuration | `oakmobdrops.reload` |
| `/omd list` | List all configured mob drops | `oakmobdrops.list` |
| `/omd stats [type] [page]` | View statistics (overview, rarest, players, mobs, variance) | `oakmobdrops.stats` |
| `/omd stats player <name>` | View detailed player statistics | `oakmobdrops.stats` |
| `/omd test <mob> <drop> [player] [--full]` | Test a drop (`--full` fires announcements and records stats) | `oakmobdrops.test` |
| `/omd clearstats` | Reset all statistics (requires confirmation) | `oakmobdrops.clearstats` |
| `/omd migrate <json\|sqlite>` | Migrate statistics between storage backends | `oakmobdrops.migrate` |

**Aliases**: `/oakmobdrops`, `/mobdrops`

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `oakmobdrops.*` | All OakMobDrops permissions | op |
| `oakmobdrops.use` | Base command access | op |
| `oakmobdrops.reload` | Reload configuration | op |
| `oakmobdrops.list` | List configured drops | op |
| `oakmobdrops.stats` | View statistics | op |
| `oakmobdrops.test` | Test drops | op |
| `oakmobdrops.clearstats` | Clear all statistics | op |
| `oakmobdrops.migrate` | Migrate statistics storage | op |

## Configuration

The plugin uses a single `config.yml` with sections for general settings, mob drop definitions, and customizable messages.

**Key settings**: looting multiplier, spawner drop control, global announcements, statistics tracking, sound effects, and search radius for drop recipients.

**Drop types**: `VANILLA`, `NEXO`, `ITEMSADDER`, `EXECUTABLE_ITEMS`

**Placeholders** (used in announcements and commands): `<player>`, `<mob>`, `<item>`, `<amount>`, `<chance>`, `<chance_fraction>`

See the included `config.yml` for full documentation and examples.

## Placeholders

Requires [PlaceholderAPI](https://www.spigotmc.org/resources/6245/). All placeholders use the `oakmobdrops` prefix.

| Placeholder | Description |
|-------------|-------------|
| `%oakmobdrops_total_kills%` | Total mobs killed (all players) |
| `%oakmobdrops_total_drops%` | Total items dropped (all players) |
| `%oakmobdrops_total_eligible_kills%` | Kills eligible for drops (all players) |
| `%oakmobdrops_player_kills%` | Mobs killed by the player |
| `%oakmobdrops_player_drops%` | Drops received by the player |
| `%oakmobdrops_player_eligible_kills%` | Eligible kills by the player |
