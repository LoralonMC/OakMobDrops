# Changelog

All notable changes to OakMobDrops will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Add configurable statistics storage backend (`storage-type: json` or `sqlite` in config.yml)
- Add SQLite storage backend with WAL mode, prepared statements, and batch transactions for better performance on large servers
- Add `/omd migrate <json|sqlite>` command to migrate statistics between storage backends
- Add `oakmobdrops.migrate` permission for the migrate command
- Add dirty tracking for statistics autosave (only writes changed records, skips no-op cycles)
- Add SQLite batch transaction support (dirty flush writes all data in a single transaction instead of four separate ones)
- Add auto-migration from JSON to SQLite on first startup when `storage-type: sqlite` is configured
- Add `paper-plugin.yml` as primary plugin descriptor (Paper 1.21.10)
- Add Brigadier command system with full tab-completion
- Add `/omd list` command to display all configured mob drops with `oakmobdrops.list` permission
- Add `/omd test --full` mode that fires announcements, records statistics, and runs commands
- Add clearstats confirmation with 30-second timeout and automatic backup before reset
- Add clickable pagination in statistics reports
- Add clickable player names in Top Farmers report (click to view player stats)
- Add PlaceholderAPI integration with 6 placeholders (`total_kills`, `total_drops`, `total_eligible_kills`, `player_kills`, `player_drops`, `player_eligible_kills`)
- Add ConfigManager with validation, conditional config merge, and cached getters
- Add MessageManager for config-driven player-facing messages
- Add `messages:` section to config.yml with ~40 customizable message keys
- Add `oakmobdrops.*` wildcard permission with explicit children
- Add `oakmobdrops.clearstats` permission for clearstats command
- Add startup validation warnings when configured drops reference missing backends
- Add startup config summary logging each mob's drops with formatted percentages
- Add `config-version: 1` footer to config.yml with validation (rejects configs from future incompatible versions)
- Add `list-mob` and `list-drop` message templates to config.yml (list output is now fully customizable)
- Add hover tooltips to pagination buttons ("Previous page" / "Next page")
- Add resolved item names with hover tooltips across all commands (`/omd list`, `/omd test`, `/omd stats`) using backend-direct API lookups (Nexo, ItemsAdder, ExecutableItems) with `getDisplayName()`/`getLore()` on `DropBackend` interface
- Add clickable `[Click to confirm]` button to clearstats warning message
- Log info message when PlaceholderAPI is not found

### Changed

- **Breaking**: All placeholders (announcements and commands) changed from `%placeholder%` to MiniMessage `<placeholder>` tags
- **Breaking**: `%chance-fraction%` placeholder renamed to `<chance_fraction>` (MiniMessage tags don't support hyphens)
- Update Paper API from 1.21.8 to 1.21.10
- Update all default colors to Oakheart hex palette (announcements, statistics reports, vanilla item names)
- Rewrite config.yml with section separators and standardized message format
- Reload command now validates config before applying (keeps old config on failure)
- Config merge now only saves when new keys are missing (prevents SnakeYAML reformatting)
- `plugin.yml` reduced to permissions-only (metadata moved to `paper-plugin.yml`, commands via Brigadier)
- **Breaking**: `oakmobdrops.admin` permission renamed to `oakmobdrops.clearstats` for per-command granularity
- Statistics page size reduced from 10 to 5 entries per page
- Backend debug mode now updates on reload (was captured once at startup)
- Model classes (DropEntry, DropSpec, MobDropConfig) converted to Java records
- Extract event listeners, commands, config, and messages from main class into dedicated classes
- Update shadow plugin to `com.gradleup.shadow` v9.3.1

### Fixed

- Fix reload never validating config (now validates before applying, rejects invalid configs)
- Fix Creeper example drop chance from 0.5 (50%) to 0.001 (0.1%)
- Fix potential NPE when player lastSeen is null in statistics report
- Fix backend reflection using static fields that could retain stale state across reloads
- Fix reload not merging new default config keys (new keys now appear after reload)
- Fix statistics pagination showing empty results when requested page exceeds total pages (now clamps to last page)
- Fix YAML list format drops (`drops: [{...}]`) not loading (list check was unreachable)

### Removed

- Remove legacy command system (`onCommand`/`onTabComplete` in main class)
- Remove `run-paper` plugin from build.gradle
- Remove CodeMC repository from build.gradle

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

[Unreleased]: https://github.com/LoralonMC/OakMobDrops/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/LoralonMC/OakMobDrops/releases/tag/v1.1.0
[1.0.0]: https://github.com/LoralonMC/OakMobDrops/releases/tag/v1.0.0
