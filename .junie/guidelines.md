# GpxPlayerDesktop Developer Guidelines

## Project Overview
GpxPlayerDesktop is a desktop application for visualizing and playing back GPX track files. It displays tracks on an interactive map and can send location data to Android devices via ADB for testing location-based applications.

## Tech Stack
- **Language**: Kotlin 2.0.0
- **UI Framework**: Jetbrains Compose 1.6.10
- **Build System**: Gradle
- **JDK Version**: Java 17
- **Key Dependencies**:
  - JavaFX (Web, Swing modules)
  - Kotlin Coroutines
  - Skiko (Skia for Kotlin)

## Project Structure
```
GpxPlayerDesktop/
├── src/
│   ├── main/
│   │   ├── kotlin/         # Kotlin source files
│   │   └── resources/      # Application resources (icons, etc.)
│   └── test/
│       ├── kotlin/         # Test source files (currently empty)
│       └── resources/      # Test resources (currently empty)
├── build.gradle.kts        # Gradle build configuration
├── gradle.properties       # Gradle and project properties
├── settings.gradle.kts     # Gradle settings
└── test.gpx                # Sample GPX file for testing
```

## Building and Running
1. **Prerequisites**:
   - JDK 17
   - Gradle (wrapper included)

2. **Build the project**:
   ```
   ./gradlew build
   ```

3. **Run the application**:
   ```
   ./gradlew run
   ```

4. **Package the application**:
   ```
   ./gradlew packageReleaseDeb      # For Linux
   ./gradlew packageReleaseExe      # For Windows
   ```

## Testing
The project has a test directory structure in place, but no tests have been implemented yet. When adding tests:

1. Place Kotlin test files in `src/test/kotlin/`
2. Place test resources in `src/test/resources/`
3. Run tests with:
   ```
   ./gradlew test
   ```

## Best Practices
1. **Code Organization**:
   - Keep UI components and business logic separate
   - Use descriptive function and variable names
   - Follow Kotlin coding conventions

2. **Feature Development**:
   - Add tests for new functionality
   - Document complex algorithms
   - Update this guide when adding significant features

3. **Performance Considerations**:
   - Be mindful of memory usage when processing large GPX files
   - Use coroutines for background operations
   - Optimize map rendering for smooth performance

4. **Cross-Platform Compatibility**:
   - Test on all target platforms (Windows, Linux)
   - Handle platform-specific paths and commands appropriately
   - Use relative paths when possible