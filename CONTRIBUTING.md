# Contributing to OakMobDrops

First off, thank you for considering contributing to OakMobDrops! It's people like you that make the Minecraft community great.

## Code of Conduct

By participating in this project, you are expected to uphold a respectful and welcoming environment for everyone.

## How Can I Contribute?

### Reporting Bugs

Before creating bug reports, please check the existing issues to avoid duplicates. When you create a bug report, include as many details as possible:

- **Use a clear and descriptive title**
- **Describe the exact steps to reproduce the problem**
- **Provide specific examples** (config files, commands used, etc.)
- **Include your environment details**:
  - Server version (e.g., Paper 1.21.1)
  - Plugin version
  - Java version
  - Other relevant plugins installed
- **Paste the full error log** from console/logs

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating an enhancement suggestion:

- **Use a clear and descriptive title**
- **Provide a detailed description** of the suggested enhancement
- **Explain why this enhancement would be useful** to most users
- **List any similar plugins or features** that implement this

### Pull Requests

1. **Fork the repository** and create your branch from `master`
2. **Follow the existing code style**:
   - Use 4 spaces for indentation (no tabs)
   - Follow Java naming conventions
   - Add JavaDoc comments for public methods
   - Keep methods focused and reasonably sized
3. **Test your changes**:
   - Build the plugin with `./gradlew build`
   - Test on a Paper 1.21+ server
   - Verify it works with different backend plugins
4. **Update documentation** if needed:
   - Update README.md for new features
   - Update config.yml comments for new options
5. **Commit with clear messages**:
   - Use present tense ("Add feature" not "Added feature")
   - Reference issues and pull requests when relevant
6. **Submit the pull request**

## Development Setup

### Prerequisites

- **Java 21** or higher
- **Git** for version control
- **Gradle** (wrapper included)
- **Paper 1.21+** test server (optional but recommended)

### Building from Source

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/OakMobDrops.git
cd OakMobDrops

# Build the plugin
./gradlew build

# The compiled JAR will be in build/libs/
```

### Project Structure

```
src/main/java/dev/oakheart/oakmobdrops/
├── OakMobDrops.java           # Main plugin class
├── DropStatistics.java        # Statistics tracking
├── announcement/              # Announcement system
├── backend/                   # Backend integrations (EI, IA, Nexo, Vanilla)
├── config/                    # Configuration loading
├── drop/                      # Drop processing logic
├── model/                     # Data models
└── util/                      # Utility classes
```

### Code Style Guidelines

- **Naming**:
  - Classes: `PascalCase`
  - Methods: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Variables: `camelCase`
- **Documentation**:
  - Add JavaDoc for all public classes and methods
  - Include `@param` and `@return` tags
  - Explain complex logic with inline comments
- **Error Handling**:
  - Use try-catch for operations that might fail
  - Log errors with appropriate severity levels
  - Provide helpful error messages to users

### Testing Your Changes

1. Build the plugin: `./gradlew build`
2. Copy `build/libs/OakMobDrops-1.0.0.jar` to your test server's `plugins/` folder
3. Start/restart your test server
4. Test the specific feature you changed
5. Check console for any errors or warnings

### Backend Integration

When adding support for new item plugins:

1. Create a new backend class in `backend/` implementing `DropBackend`
2. Add the drop type to `model/DropType.java`
3. Register it in `backend/DropRouter.java`
4. Update documentation and config examples

## Questions?

Feel free to open an issue with the "question" label if you need help or clarification on anything!

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
