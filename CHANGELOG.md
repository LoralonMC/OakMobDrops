# Changelog

All notable changes to OakMobDrops will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2025-11-17

### Added
- **bStats Integration**: Anonymous usage statistics with 4 custom metrics
  - Most used backend type
  - Total drops configured
  - Average drop chance across all drops
  - Announcement usage percentage
- **Backend Version Detection**: All backends now log their detected API version on startup
- **Per-Player Statistics**: New command `/oakmobdrops stats players <name>` for detailed player breakdown
- **Enhanced Player Tracking**:
  - First seen timestamp
  - Eligible kills counter (kills that were eligible for drops)
  - Spawner kills counter (kills from spawner-spawned mobs)
  - Favorite drops list
- **Per-Mob Breakdown**: Statistics now show eligible and spawner kills per mob type
  - Compact display format: "Zombie: 543 (500 eligible, 100 spawner)"

### Changed
- **Statistics Commands**: Simplified command structure with clear, single-purpose aliases
  - `/oakmobdrops stats` - Overview
  - `/oakmobdrops stats rarest` - Rarest drops
  - `/oakmobdrops stats players` - Top farmers leaderboard
  - `/oakmobdrops stats players <name>` - Per-player detailed stats
  - `/oakmobdrops stats mobs` - Mob breakdown
  - `/oakmobdrops stats variance` - Expected vs actual rates
- **Percentage Formatting**: Smart formatting removes trailing zeros (10.0000% → 10%, 10.0005% → 10.0005%)

### Fixed
- Statistics persistence now correctly saves all per-mob tracking data
- Stats file backwards compatibility with existing v1.0.0 stats files

### Technical
- Updated shadow plugin to v8.1.8 for Java 21 compatibility
- bStats dependency added with proper relocation to prevent conflicts

## [1.0.0] - 2025-10-31

### Initial Release

#### Added
- Multi-backend support for ExecutableItems, ItemsAdder, Nexo, and vanilla items
- Multiple independent drops per mob with configurable drop rates
- Looting enchantment support with configurable multipliers
- Spawner tracking system to prevent/allow spawner farming
- Slime split inheritance for spawner tags
- Comprehensive statistics tracking system with multiple report types:
  - Overview statistics
  - Rarest drops tracking
  - Top farmers leaderboard
  - Mob-specific breakdowns
  - Expected vs actual drop rates
- Announcement system with MiniMessage formatting support
- Per-drop and global announcement configuration
- Command execution on drop success with placeholder support
- Sound effects for rare drops
- Range-based drop amounts (e.g., "5-10" items)
- Drop at location or direct player inventory options
- Per-mob spawner drop configuration
- Statistics auto-save with configurable intervals
- Comprehensive admin commands:
  - `/oakmobdrops reload` - Reload configuration
  - `/oakmobdrops stats` - View statistics with pagination
  - `/oakmobdrops test` - Test drops without killing mobs
  - `/oakmobdrops clearstats` - Reset statistics
- Tab completion for all commands
- Debug mode for troubleshooting
- Configurable player search radius for drop recipients

#### Technical
- Built for Paper 1.21+
- Java 21 requirement
- Fully documented configuration with inline examples
- Modular architecture with clean separation of concerns
- Async announcement broadcasting
- Persistent data containers for spawner tracking
- ThreadLocalRandom for efficient random number generation

[1.1.0]: https://github.com/LoralonMC/OakMobDrops/releases/tag/v1.1.0
[1.0.0]: https://github.com/LoralonMC/OakMobDrops/releases/tag/v1.0.0