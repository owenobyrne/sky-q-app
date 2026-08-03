# Sky Q App

Android TV app replicating Sky Q's look & feel, backed by TVHeadend instead of a Sky subscription.

## Stack

- **Kotlin 2.1.20** + **Jetpack Compose TV** (`androidx.tv:tv-material:1.1.0`)
- **AGP 8.13.2**, Gradle 8.13, compileSdk 36, targetSdk 34, minSdk 23
- **Media3/ExoPlayer** for video (HLS, LL-HLS, or direct HTSP)
- **Retrofit + OkHttp** for the TVHeadend HTTP API
- **Coil** for channel icon images

## TVHeadend

Server host, port and credentials live in `AppSettings` (set via the Settings screen,
which can also scan the local /24 for a server). Defaults to port 9981; HTSP is always
HTTP port + 1.

- HTTP API — **Digest auth** (not Basic), implemented manually in `TvHeadendClient.kt`
- EPG API: `/api/epg/events/grid` — `start` is a **row offset, not a timestamp**; fetched
  in 500-event chunks and filtered to the window client-side
- Channel icons: `{base}/{icon_public_url}` (e.g. `imagecache/17`)
- Encrypted services are fetched and filtered out of the channel list

Three streaming modes, selectable in Settings (`StreamingMode`):

| Mode | URL / transport | Notes |
|------|-----------------|-------|
| `HTSP` | `htsp:///{channelId}` via `HtspDataSource` | Direct subscription, re-muxed to MPEG-TS by `TsMuxer`. Fastest channel changes; live edge only. |
| `HLS` | `/hls/channel/{uuid}.m3u8?profile=hls-transcode` | Server-side DVR buffer. |
| `HLS_LL` | `/hls/channel/{uuid}.m3u8?profile=hls-ll` | Default. Plays 3 s behind live to avoid live-edge stalls. |

## Architecture

```
MainActivity
└── SkyQApp
    ├── TvGuideScreen          (the primary — and only — content screen)
    │   ├── InfoPanel          (focused programme details)
    │   └── EpgGrid            (proportional-width grid, cells drawn on a Canvas)
    ├── SettingsScreen         (server, credentials, streaming mode)
    └── VideoOverlay           (shared surface: animates between guide preview and fullscreen)
        └── BrowsePane         (second muted player — peek at other channels while watching)
```

Two ViewModels, both hoisted in `SkyQApp`: `TvGuideViewModel` (channels + EPG) and
`VideoViewModel` (owns both ExoPlayers and channel tuning).

## Key files

| File | Purpose |
|------|---------|
| `data/settings/AppSettings.kt` | Persisted config; loads async, gate on `awaitReady()` |
| `data/api/TvHeadendClient.kt` | OkHttp client with Digest auth + stream URL builders |
| `data/api/TvHeadendApi.kt` | Retrofit interface (channels, services, EPG) |
| `data/repository/EpgRepository.kt` | Chunked EPG fetch, incremental per-channel merge |
| `data/htsp/` | HTSP connection, wire format, `DataSource`, MPEG-TS muxer |
| `ui/guide/TvGuideViewModel.kt` | Loads channels + EPG, pre-builds grid cells off-thread |
| `ui/guide/EpgGrid.kt` | Canvas-drawn EPG grid, shared horizontal scroll state |
| `ui/video/VideoViewModel.kt` | Both ExoPlayers, debounced channel tuning |
| `ui/video/VideoOverlay.kt` | Player surface + fullscreen OSD |
| `ui/video/AmlogicVideoRenderer.kt` | Chromecast HD Codec2 output-pool workaround |
| `ui/theme/Color.kt` | Sky Q blue palette |

## EPG grid approach

- 24-hour window (`WINDOW_HOURS`), 7dp/minute
- `LazyColumn` virtualises vertically; the per-row `Canvas` spans the full 24 h but only
  draws cells within the scroll viewport (+30 min buffer)
- Shared `rememberScrollState()` across all rows for synced horizontal scrolling
- One focusable per row's programme area; Left/Right move `selectedCellIdx` rather than
  traversing hundreds of focus targets
- `buildCells()` fills gaps between programmes with empty cells. It runs in
  `TvGuideViewModel` on `Dispatchers.Default` — **never in composition**

## Threading

The UI thread is the scarce resource on this hardware; keep work off it.

- `AppSettings` loads on IO (EncryptedSharedPreferences hits the Keystore). Anything
  needing config suspends on `AppSettings.awaitReady()`.
- `isAmlogicDevice` (a `MediaCodecList` query) is warmed on a background coroutine in
  `SkyQApplication`.
- Media-source factories are lazy and materialise inside `withContext(Dispatchers.IO)`;
  only `setMediaSource`/`prepare` happen on main.
- `EpgRepository` coalesces progressive emissions (`EMIT_INTERVAL_MS`) and preserves the
  `List` instance for channels that didn't change, so unchanged grid rows don't re-lay-out.
- Don't collect the guide state high in the tree — read `viewModel.state.value` from key
  handlers instead, or every EPG emission recomposes the whole UI.

## Colours

| Token | Hex | Use |
|-------|-----|-----|
| `SkyDarkest` | `#0128CC` | App / EPG background |
| `SkyNavy` | `#0028D3` | Channel column, grid header |
| `SkyCellBg` | `#0222B3` | Unfocused programme cell |
| `SkyHighlight` | `#1E3ACC` | Currently-airing cell |
| `SkySelected` | `#2B52E8` | Focused cell |

## Build & run

Open in Android Studio Panda+. Sync will download Gradle 8.13 and all deps.
Sideload to a Google TV dongle via ADB — emulator video playback is unreliable.

```bash
adb connect <tv-ip>
adb install app/build/outputs/apk/debug/app-debug.apk
```
