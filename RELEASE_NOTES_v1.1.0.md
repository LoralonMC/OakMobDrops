# OakMobDrops v1.1.0 Release Notes

## What's New in v1.1.0

### Statistics Overhaul
This release brings major improvements to the statistics system with enhanced tracking and more detailed player insights.

#### Per-Player Statistics
- New command: `/oakmobdrops stats players <name>` for detailed individual player breakdowns
- Track when players were first seen and last active
- View eligible kills (kills that could drop items) vs total kills
- See spawner kills separately from natural mob kills
- Display favorite drops and rarest items obtained

#### Enhanced Mob Tracking
- Per-mob breakdown now shows:
  - Total kills per mob type
  - Eligible kills (how many were eligible for drops)
  - Spawner kills (how many were from spawners)
- Compact display format: `Zombie: 543 (500 eligible, 100 spawner)`

#### Simplified Commands
Statistics commands have been streamlined for clarity:
- `/oakmobdrops stats` - Overview
- `/oakmobdrops stats rarest` - Rarest drops
- `/oakmobdrops stats players` - Top farmers leaderboard
- `/oakmobdrops stats players <name>` - Individual player stats
- `/oakmobdrops stats mobs` - Mob breakdown
- `/oakmobdrops stats variance` - Expected vs actual rates

### bStats Integration
- Anonymous usage statistics now tracked via bStats
- Helps understand plugin usage patterns
- 4 custom metrics:
  - Most used backend type
  - Total drops configured
  - Average drop chance
  - Announcement usage

### Backend Improvements
- All backends now log their detected API version on startup
- Better visibility into ItemsAdder, ExecutableItems, and Nexo integration
- Helps troubleshoot compatibility issues

### Quality of Life
- Smart percentage formatting removes trailing zeros (10.0000% → 10%)
- Better backward compatibility with v1.0.0 stats files
- Fixed statistics persistence for per-mob tracking data

## Installation

1. Download `OakMobDrops-1.1.0.jar`
2. Stop your server
3. Replace the old JAR in your `plugins` folder
4. Start your server
5. Your existing configuration and statistics will be preserved

## Compatibility

- **Minecraft Version**: Paper 1.21+
- **Java Version**: 21 or higher
- **Supported Item Plugins**:
  - ExecutableItems
  - ItemsAdder
  - Nexo
  - Vanilla items

## Upgrading from v1.0.0

This is a backward-compatible update. Your existing:
- Configuration files will work without changes
- Statistics data will be preserved and enhanced with new tracking
- Drop configurations remain unchanged

Simply replace the JAR file and restart your server.

## Full Changelog

See [CHANGELOG.md](CHANGELOG.md) for complete details.

## Support

- **Issues**: [GitHub Issues](https://github.com/LoralonMC/OakMobDrops/issues)
- **Wiki**: [GitHub Wiki](https://github.com/LoralonMC/OakMobDrops/wiki)

## License

MIT License - See [LICENSE](LICENSE) for details.
