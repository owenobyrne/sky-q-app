# Sky Q App

Android TV app replicating Sky Q's look & feel, backed by TVHeadend instead of a Sky subscription.

## Stack

- **Kotlin 2.1.20** + **Jetpack Compose TV** (`androidx.tv:tv-material:1.1.0`)
- **AGP 8.13.2**, Gradle 8.13, compileSdk 35, minSdk 23
- **Media3/ExoPlayer** for video (MPEG-TS streams from TVHeadend)
- **Retrofit + OkHttp** for TVHeadend HTTP API
- **Coil** for channel icon images

## TVHeadend

- HTTP API: `http://192.168.1.7:9981/` — **Digest auth** (not Basic), user: `emby`, pass: `emby`
- HTSP: `192.168.1.7:9982` (unused so far — HTTP streaming is sufficient for MVP)
- Stream URL: `http://192.168.1.7:9981/stream/channel/{uuid}`
- Channel icons: `http://192.168.1.7:9981/{icon_public_url}` (e.g. `imagecache/17`)
- EPG API: `/api/epg/events/grid?limit=9999` — `start` param is a **row offset, not a timestamp**; fetch all and filter client-side
- Digest auth is implemented manually in `TvHeadendClient.kt` (no extra library)

## Architecture

```
MainActivity
└── SkyQApp (NavHost)
    ├── AppShell (persistent sidebar + content area)
    │   ├── Sidebar (logo, clock, LivePreview, nav items)
    │   └── [content area]
    │       ├── HomeScreen        (placeholder)
    │       ├── TvGuideScreen     (EPG — primary screen)
    │       │   └── EpgGrid       (custom proportional-width grid)
    │       └── RecordingsScreen  (placeholder)
    └── PlayerScreen              (full-screen, no sidebar)
```

## Key files

| File | Purpose |
|------|---------|
| `data/api/TvHeadendClient.kt` | OkHttp client with Digest auth + stream URL builder |
| `data/api/TvHeadendApi.kt` | Retrofit interface (channels, EPG) |
| `data/repository/EpgRepository.kt` | Fetch + client-side time-window filter |
| `ui/guide/EpgGrid.kt` | Proportional-width EPG grid, shared horizontal scroll state |
| `ui/guide/TvGuideViewModel.kt` | Loads channels + EPG, exposes preview channel UUID |
| `ui/shell/LivePreview.kt` | Muted ExoPlayer embedded in sidebar |
| `ui/player/PlayerScreen.kt` | Full-screen ExoPlayer (with audio) |
| `ui/theme/Color.kt` | Sky Q navy colour palette |

## EPG grid approach

- 6-hour window, 7dp/minute
- Shared `rememberScrollState()` across all channel rows for sync'd horizontal scroll
- `buildCells()` fills gaps between programmes with empty cells so focus traversal works
- Currently-airing events: `SkyHighlight` blue; focused: `SkySelected` brighter blue

## Colours

| Token | Hex | Use |
|-------|-----|-----|
| `SkyDarkest` | `#070E2A` | App background |
| `SkyNavy` | `#0D1B4B` | Sidebar, channel column |
| `SkyBlue` | `#122266` | Info panel |
| `SkyHighlight` | `#1E3ACC` | Live programme cells |
| `SkySelected` | `#2B52E8` | Focused cell |

## Build & run

Open in Android Studio Panda+. Sync will download Gradle 8.13 and all deps.
Sideload to Google TV dongle via ADB — emulator video playback is unreliable.

```bash
adb connect <tv-ip>
adb install app/build/outputs/apk/debug/app-debug.apk
```
