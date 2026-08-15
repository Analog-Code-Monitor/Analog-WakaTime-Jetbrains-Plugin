# Analog WakaTime for JetBrains

- This plugin does not compile, parse, or read your source code contents. It physically cannot see your code text. It only reacts to IDE editor events and stores lightweight metadata such as file path, language, time spent, keystroke counts, and line deltas.
- Device-flow authentication is supported.
- Activity is buffered locally and synchronized with the Analog WakaTime backend.
- The status bar widget opens the Analog WakaTime website directly from the IDE.

## What It Does

Analog WakaTime tracks coding activity inside JetBrains IDEs and sends grouped activity records to the Analog WakaTime backend.

Current functionality:

- device-flow login
- manual API token entry
- local persistence of auth token and unsynced activity
- activity tracking for local files
- periodic sync and connectivity checks
- status bar widget with elapsed time
- quick actions for login, logout, stats, and force sync

## Privacy

The plugin tracks metadata, not source code contents.

Tracked data includes:

- file path
- detected language
- time spent
- keystroke count
- line additions and deletions
- project path and project name

The plugin does not upload file contents.

## Local Storage

The plugin stores:

- auth token in local plugin state
- unsynced activities in local plugin state
- installation identifier for telemetry

## Development

Requirements:

- Java 21
- Gradle wrapper from this repository

Useful commands:

```bash
./gradlew runIde
./gradlew test
./gradlew buildPlugin
```

## CI/CD

This repository includes GitHub Actions workflows for:

- CI on push and pull request
- building the plugin ZIP artifact
- publishing a GitHub Release when a tag like `v1.2.3` is pushed

The release workflow builds the plugin with the tag version and uploads the generated ZIP from `build/distributions`.
# Analog-WakaTime-Jetbrains-Plugin
