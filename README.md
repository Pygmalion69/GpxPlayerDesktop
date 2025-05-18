# GpxPlayerDesktop

GpxPlayerDesktop is a modern desktop application built with Kotlin and Jetpack Compose for Desktop. It allows playback of `.gpx` track files with live location simulation, ideal for Android development and GPS testing.

![Screenshot](assets/screenshot.png)

## Features

- 📍 GPX file loading and map visualization
- 🛰️ Simulated playback with speed control
- 🎯 Location marker with heading orientation
- 📡 ADB integration to send mock locations to connected Android devices
- ☁️ Interface with [GpxPlayer for Android](https://github.com/nitri/GpxPlayer)
- 🌍 Interactive map (Leaflet.js) embedded via JavaFX WebView
- 💾 Persistent ADB path configuration
- 🖥️ Cross-platform support (Windows/Linux)

## Getting Started

### Prerequisites

- Java 17+
- Android device with developer options enabled
- `adb` (Android Debug Bridge) installed

### Running

```
./gradlew run
```

or build native distributions:

```
./gradlew packageDistributionForCurrentOS
```

### GPX Playback

1. Click **Load GPX** to select a `.gpx` file.
2. Adjust the playback speed using the slider.
3. Press **Play** to simulate the route on the map.
4. Use **Stop** to cancel simulation.

