# Dez Sports

A personal Android scores app in the style of Apple Sports, built with Kotlin and Jetpack Compose and tuned for the Pixel Fold.

**Leagues:** NFL, college football, NBA, college basketball, NHL, MLB and PGA golf.

## Features

- Live scores, a "For You" feed of your teams, and a home-screen widget
- Game pages with box scores, play-by-play, momentum, win probability and an 8-bit Gamecast
- NHL starting lines
- Team pages with:
  - the season as a story timeline
  - league ranks for every team stat
  - hot/cold form
  - season shot maps
- Player pages, head-to-head history, "Tale of the Tape" player comparisons, and stat leaders with full league lists
- A **Dez Score** (0–100) rating how fun each finished game was
- **Settings:** pick your own teams. Tap the gear on the Scores tab.

## Build

Requires JDK 21 and the Android SDK (compileSdk 36).

```sh
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

## Data

Scores and stats come from ESPN's public JSON endpoints, and NHL lines come from DailyFaceoff. Neither is an official or licensed API, so this is a personal project, not for distribution through app stores.
