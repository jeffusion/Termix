<div align="center">
  <a href="#readme-top"></a>
  
  <!-- Placeholder: App Logo / Banner -->
  <!-- Suggested image: 1024×500 banner with app icon on the left and "Termix" name + tagline on the right -->
  <img src="docs/images/banner.webp" alt="Termix Banner" width="80%">

  <h1>Termix</h1>
  <p><strong>A modern, Material 3 inspired terminal emulator for Android</strong></p>

  <p>
    <a href="https://github.com/jeffusion/Termix/releases">
      <img src="https://img.shields.io/github/v/release/jeffusion/Termix?include_prereleases&label=latest&style=flat-square" alt="Latest Release">
    </a>
    <a href="https://github.com/jeffusion/Termix/blob/main/LICENSE">
      <img src="https://img.shields.io/github/license/jeffusion/Termix?style=flat-square&color=blue" alt="License">
    </a>
    <a href="https://github.com/jeffusion/Termix/actions">
      <img src="https://img.shields.io/github/actions/workflow/status/jeffusion/Termix/ci.yml?style=flat-square&label=CI" alt="CI Status">
    </a>
    <img src="https://img.shields.io/badge/minSdk-Android%208.0+-21B6A8?style=flat-square&logo=android" alt="Android 8.0+">
  </p>

  <p>
    <a href="README_CN.md">简体中文</a>
  </p>
</div>

---

## Table of Contents

- [About](#about)
- [Features](#features)
- [Screenshots](#screenshots)
- [Download](#download)
- [Tech Stack](#tech-stack)
- [Building](#building)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## About

**Termix** is a modern terminal emulator for Android built with [Material Design 3](https://m3.material.io/). It is designed as a modern replacement for the classic [Jackpal Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator).

Termix is powered by the battle-tested `TerminalView` from [Termux](https://github.com/termux/termux-app), delivering smooth terminal rendering, full keyboard and mouse support, and a polished user experience. The project was forked from [ReTerminal](https://github.com/RohitKushvaha/ReTerminal) and has since evolved with a modern Jetpack Compose UI, deeper customization, and native support for multiple Linux environments.

> **Note:** This project is under active development. Issues and PRs are welcome!

---

## Features

### Terminal Experience
- **High-performance rendering** — Built on Termux's `TerminalView` with support for true color, 256-color, and standard 16-color palettes.
- **Multi-session support** — Run multiple terminal sessions simultaneously and switch quickly via a navigation drawer or a top tab bar.
- **Virtual keys** — A built-in, swipeable virtual key bar (ESC, CTRL, ALT, arrows, HOME, END, PGUP, PGDN) for devices without a physical keyboard.
- **Configurable keyboard shortcuts** — Fully customizable shortcuts for paste, new/close session, switch session, and more.
- **Session persistence** — Sessions are kept alive by a foreground service, so you won’t lose state when switching apps.

### Multi-Environment Support
Termix ships with built-in support for several environments, giving you a full Linux command-line experience on Android without complex setup:
- **Alpine Linux** — Lightweight and efficient container-like environment.
- **Arch Linux ARM** — Feature-rich rolling-release distribution for ARM devices.
- **Android Shell** — Direct access to the device's native shell.
- **Root mode** — On rooted devices, run Alpine or Arch sessions with root privileges.

### Deep Customization
- **Color schemes** — Curated built-in palettes with automatic dark-mode adaptation; advanced users can customize via `colors.properties`.
- **Custom fonts** — Import any `.ttf` font file to personalize your terminal.
- **Custom background** — Set a background image for the terminal with independent transparency control.
- **Layout modes** — Choose between a **Classic Drawer** or a **Top Tab Bar** layout.
- **Theming** — Native Android Monet dynamic colors, AMOLED pure-black dark mode.
- **Scrollback buffer** — Configurable from 500 to 50,000 lines.
- **Default shell** — Choose Bash, Ash, or Zsh as your default shell.

### Other Highlights
- **Edge-to-edge immersive layout** — Maximizes screen real estate with automatic system-bar adaptation.
- **Crash handler** — Built-in crash capture and logging for improved stability.
- **Fastlane metadata** — Pre-configured for distribution on F-Droid and similar stores.

---

## Screenshots

Termix supports both **Phone Mode** and **Desktop Mode** layouts. The screenshots below show the same feature set adapted to each layout.

### Phone Mode

<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="docs/images/screenshots/phone/01.jpg" width="200px" alt="Phone Mode - Main Terminal">
        <br/><sub>Main Terminal</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/phone/02.jpg" width="200px" alt="Phone Mode - Session Management">
        <br/><sub>Session Management</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/phone/03.jpg" width="200px" alt="Phone Mode - Settings">
        <br/><sub>Settings</sub>
      </td>
    </tr>
    <tr>
      <td align="center">
        <img src="docs/images/screenshots/phone/04.jpg" width="200px" alt="Phone Mode - Color Schemes">
        <br/><sub>Color Schemes</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/phone/05.jpg" width="200px" alt="Phone Mode - Keyboard Shortcuts">
        <br/><sub>Keyboard Shortcuts</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/phone/06.jpg" width="200px" alt="Phone Mode - Dark Theme">
        <br/><sub>Dark Theme</sub>
      </td>
    </tr>
  </table>
</div>

### Desktop Mode

<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="docs/images/screenshots/desktop/01.pc.jpg" width="260px" alt="Desktop Mode - Main Terminal">
        <br/><sub>Main Terminal</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/desktop/02.pc.jpg" width="260px" alt="Desktop Mode - Session Management">
        <br/><sub>Session Management</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/desktop/03.pc.jpg" width="260px" alt="Desktop Mode - Settings">
        <br/><sub>Settings</sub>
      </td>
    </tr>
    <tr>
      <td align="center">
        <img src="docs/images/screenshots/desktop/04.pc.jpg" width="260px" alt="Desktop Mode - Color Schemes">
        <br/><sub>Color Schemes</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/desktop/05.pc.jpg" width="260px" alt="Desktop Mode - Keyboard Shortcuts">
        <br/><sub>Keyboard Shortcuts</sub>
      </td>
      <td align="center">
        <img src="docs/images/screenshots/desktop/06.pc.jpg" width="260px" alt="Desktop Mode - Dark Theme">
        <br/><sub>Dark Theme</sub>
      </td>
    </tr>
  </table>
</div>

---

## Download

### APK
Grab the latest APK from the [**Releases**](https://github.com/jeffusion/Termix/releases) page.

> You may need to enable "Install from unknown sources" to install the APK.

### Requirements
- **OS:** Android 8.0 (API 26) or higher
- **Architectures:** `arm64-v8a`, `armeabi-v7a`, `x86_64`
- **Permissions:**
  - Storage (custom fonts/backgrounds and file access)
  - Notifications (foreground service status)
  - Internet (downloads required components on first run)

---

## Tech Stack

| Category | Technology |
| :--- | :--- |
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material Design 3 |
| Terminal Core | [Termux TerminalView / TerminalEmulator](https://github.com/termux/termux-app) |
| Build System | Gradle (Kotlin DSL) |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 9.0 (API 28) |
| JDK | 17 |
| CI/CD | GitHub Actions |

### Project Structure

```
Termix/
├── app/                          # Application entry module
├── core/
│   ├── main/                     # Core business logic & UI (Compose, settings, session management)
│   ├── components/               # Reusable Compose components & preference widgets
│   ├── resources/                # Shared resources (strings, themes)
│   ├── terminal-emulator/        # Terminal emulator engine (forked from Termux)
│   └── terminal-view/            # Terminal rendering view (forked from Termux)
├── fastlane/                     # Store metadata & screenshots
└── .github/workflows/            # GitHub Actions CI/CD
```

---

## Building

### Prerequisites
1. Install [Android Studio](https://developer.android.com/studio) (latest stable recommended).
2. Ensure JDK 17 is configured.
3. Clone the repository:

```bash
git clone https://github.com/jeffusion/Termix.git
cd Termix
```

### Debug Build
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Development Workflow

For day-to-day development, the repository provides a small set of convenience commands for regenerating maintained assets and producing a testable APK.
Most contributors will mainly use `make debug-apk`, while `make icons` is only needed when launcher assets are updated.

```bash
make icons      # regenerate launcher icons when branding assets change
make debug-apk  # build app/build/outputs/apk/debug/app-debug.apk
make all        # regenerate maintained assets, then build the debug APK
```

### Release Build
```bash
./gradlew assembleRelease
```
> **Signing:** Release builds support signing via `signing.properties` or environment variables. If no signing config is provided, the build falls back to an embedded test key.

---

## Contributing

Contributions of all kinds are welcome!

1. **Fork** the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m 'Add some amazing feature'`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a **Pull Request**

### Guidelines
- Follow the existing Kotlin code style.
- Use Jetpack Compose for new UI features whenever possible.
- Make sure `./gradlew assembleDebug` builds successfully before submitting.

---

## License

Termix is licensed under the [MIT License](LICENSE).

```
Copyright (c) 2024 Rohit Kushvaha

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
```

---

## Acknowledgements

- **[Termux](https://github.com/termux/termux-app)** — For the powerful and stable `TerminalView` and `TerminalEmulator` foundation.
- **[ReTerminal](https://github.com/RohitKushvaha/ReTerminal)** — The project Termix was forked from.
- **[Jackpal Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)** — The pioneer of Android terminal emulators.

---

<div align="center">
  <p><sub>Made with ❤️ for the Android terminal community</sub></p>
</div>
