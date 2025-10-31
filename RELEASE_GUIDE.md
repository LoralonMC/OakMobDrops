# Release Guide

This guide explains how to create and publish new releases of OakMobDrops.

## Automated Release Process

The project uses GitHub Actions to automatically build and release the plugin when you create a new version tag.

### Step 1: Update Version Number

1. Update the version in `build.gradle`:
```gradle
version = '1.1.0'
```

2. Update the version in `src/main/resources/plugin.yml` if needed:
```yaml
version: '1.1.0'
```

3. Update the version in README.md if referenced

### Step 2: Commit and Push Changes

```bash
git add build.gradle src/main/resources/plugin.yml
git commit -m "Bump version to 1.1.0"
git push origin master
```

### Step 3: Create and Push a Tag

```bash
# Create an annotated tag
git tag -a v1.1.0 -m "Release v1.1.0

New Features:
- Feature 1
- Feature 2

Bug Fixes:
- Fix 1
- Fix 2
"

# Push the tag to GitHub
git push origin v1.1.0
```

### Step 4: GitHub Actions Takes Over

Once you push the tag:
1. The release workflow automatically triggers
2. The plugin is built using Gradle
3. A GitHub release is created with the JAR file attached
4. Release notes are auto-generated from commits

### Step 5: Edit Release Notes (Optional)

1. Go to https://github.com/YOUR_USERNAME/OakMobDrops/releases
2. Find your new release
3. Click "Edit release"
4. Add or improve the release notes:
   - Highlight major features
   - List bug fixes
   - Note any breaking changes
   - Add upgrade instructions if needed

## Release Notes Template

Use this template when editing release notes:

```markdown
## What's New in v1.1.0

### New Features
- Added support for XYZ
- Improved ABC functionality

### Bug Fixes
- Fixed issue with spawner drops
- Resolved statistics not saving

### Changes
- Updated Paper API to 1.21.1
- Improved performance for drop calculations

### Breaking Changes
**Important:** This release includes breaking changes!
- Config format updated for XYZ - see migration guide below

### Migration Guide
If upgrading from v1.0.0:
1. Backup your config.yml
2. Update the following sections...

## Installation

Download `OakMobDrops-1.1.0.jar` and place it in your server's `plugins/` folder.

**Requirements:**
- Paper 1.21+
- Java 21+

## Full Changelog
https://github.com/YOUR_USERNAME/OakMobDrops/compare/v1.0.0...v1.1.0
```

## Version Numbering

Follow [Semantic Versioning](https://semver.org/):

- **MAJOR** version (1.x.x): Breaking changes
- **MINOR** version (x.1.x): New features, backwards compatible
- **PATCH** version (x.x.1): Bug fixes, backwards compatible

Examples:
- `v1.0.0` → `v1.0.1`: Bug fix
- `v1.0.0` → `v1.1.0`: New feature
- `v1.0.0` → `v2.0.0`: Breaking change

## Pre-release Versions

For beta or release candidate versions:

```bash
git tag -a v1.1.0-beta.1 -m "Beta release for testing"
git push origin v1.1.0-beta.1
```

Mark the release as "pre-release" on GitHub.

## Hotfix Process

For urgent bug fixes:

1. Create a hotfix branch: `git checkout -b hotfix/1.0.1`
2. Make the fix and test thoroughly
3. Update version to `1.0.1`
4. Commit and merge to master
5. Create and push tag: `v1.0.1`

## Rollback

If a release has critical issues:

1. Delete the tag locally: `git tag -d v1.1.0`
2. Delete the tag remotely: `git push origin :refs/tags/v1.1.0`
3. Delete the GitHub release
4. Fix issues and re-release with a new patch version (v1.1.1)

## Checklist Before Release

- [ ] All tests pass locally
- [ ] Build is successful: `./gradlew build`
- [ ] Tested on Paper 1.21+ server
- [ ] Tested with backend plugins (EI, IA, Nexo)
- [ ] Updated CHANGELOG or release notes
- [ ] Version bumped in all files
- [ ] No sensitive data in code or config
- [ ] Documentation is up to date