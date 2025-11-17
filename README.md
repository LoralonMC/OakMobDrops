# OakMobDrops

A feature-rich Minecraft Paper plugin for advanced custom mob drop management with multi-backend support and comprehensive statistics tracking.

## Features

- **Multi-Backend Support**: Seamlessly integrates with ExecutableItems, ItemsAdder, Nexo, and vanilla Minecraft items
- **Multiple Independent Drops**: Configure multiple drops per mob, each with independent drop rates
- **Looting Enchantment Support**: Configurable looting enchantment multipliers for drop chances
- **Spawner Tracking**: Track and control drops from spawner-spawned mobs, with slime split inheritance
- **Comprehensive Statistics**: Track kills, drops, rates, and top farmers with detailed analytics
- **Announcement System**: Customizable global and per-drop announcements using MiniMessage formatting
- **Command Execution**: Execute console commands when rare drops occur (perfect for DiscordSRV integration, economy rewards, etc.)
- **Flexible Configuration**: Range-based amounts, per-mob settings, and location-based or direct drops

## Requirements

- **Server**: Paper 1.21+ (or any Paper-based fork)
- **Java**: 21+
- **Optional Dependencies**: ExecutableItems, ItemsAdder, Nexo, SCore (plugin detects which are available)

## Installation

1. Download the latest release from the [Releases](../../releases) page
2. Place the JAR file in your server's `plugins` folder
3. Restart your server
4. Configure the plugin by editing `plugins/OakMobDrops/config.yml`
5. Run `/oakmobdrops reload` to apply your changes

## Configuration

The plugin uses a straightforward YAML configuration. Here's a basic example:

```yaml
settings:
  debug: false
  use-looting-enchantment: true
  looting-multiplier: 0.5
  allow-spawner-drops: false
  enable-statistics: true
  global-announcement: "<gold><bold>⚡</bold></gold> <yellow>%player%</yellow> <gray>just received</gray> <aqua>%amount%x %item%</aqua> <gray>from a</gray> <red>%mob%</red><gray>!</gray>"

mobs:
  ZOMBIE:
    enabled: true
    require-player-kill: true
    allow-spawner-drops: false
    drops:
      - id: zombie_pet
        chance: 0.0001  # 0.01% = 1 in 10,000
        type: NEXO
        item-id: nm_plushie_zombie
        amount: 1
        drop-at-location: true
        announcement: "<dark_green><bold>🧟 ZOMBIE PET!</bold></dark_green> <green>%player%</green> <gray>tamed an undead companion!</gray>"
        play-sound: true
        commands:
          - "discordsrv broadcast 🎉 %player% just received a %item%!"
```

See the included `config.yml` for comprehensive examples and documentation.

### Configuration Highlights

- **Drop Types**: EXECUTABLE_ITEMS, ITEMSADDER, NEXO, VANILLA
- **Amount Ranges**: Specify ranges like "5-10" for random amounts
- **Announcements**: Use "global" for global message, custom MiniMessage string, or null for no announcement
- **Commands**: Execute console commands with placeholder support when drops occur
- **Placeholders**: `%player%`, `%mob%`, `%item%`, `%amount%`, `%chance%`, `%chance-fraction%`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/oakmobdrops` | Show plugin help | `oakmobdrops.use` |
| `/oakmobdrops reload` | Reload configuration | `oakmobdrops.reload` |
| `/oakmobdrops stats [type] [page]` | View statistics | `oakmobdrops.stats` |
| `/oakmobdrops test <mob> <drop> [player]` | Test a drop | `oakmobdrops.test` |
| `/oakmobdrops clearstats` | Reset all statistics | `oakmobdrops.admin` |

**Aliases**: `/omd`, `/mobdrops`

### Statistics Types

- `overview` - General statistics summary
- `rare`/`rarest` - Rarest drops obtained
- `farmers`/`top`/`players` - Top players by kills
- `mobs`/`breakdown` - Per-mob statistics
- `variance`/`expected` - Expected vs actual drop rates

## Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `oakmobdrops.use` | Base command access | op |
| `oakmobdrops.reload` | Reload configuration | op |
| `oakmobdrops.stats` | View statistics | op |
| `oakmobdrops.test` | Test drops | op |
| `oakmobdrops.admin` | All permissions | op |

## Building from Source

```bash
git clone https://github.com/YourUsername/OakMobDrops.git
cd OakMobDrops
./gradlew build
```

The compiled JAR will be in `build/libs/`.

## Support

If you encounter any issues or have suggestions:

- Open an issue on [GitHub Issues](../../issues)
- Check existing issues for solutions
- Provide server version, plugin version, and error logs when reporting bugs

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Credits

Created by **Loralon**

### Third-Party Libraries

- [Paper API](https://papermc.io/) - Minecraft server platform
- [Kyori Adventure](https://docs.advntr.dev/) - Text formatting and messaging

### Supported Plugins

- [ExecutableItems](https://www.spigotmc.org/resources/77578/)
- [ItemsAdder](https://www.spigotmc.org/resources/73355/)
- [Nexo](https://mcmodels.net/products/13172/nexo)

---

**Enjoy using OakMobDrops? Give it a star on GitHub!**
