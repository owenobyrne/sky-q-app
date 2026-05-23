# Project Plan

## Done

- [x] Project scaffold (AGP 8.13.2, Kotlin 2.1.20, Compose TV 1.1.0)
- [x] TVHeadend HTTP API client with manual Digest auth
- [x] Channel list + EPG data layer (client-side time-window filtering)
- [x] App shell — persistent sidebar + swappable content area
- [x] Sidebar — Sky logo, live clock, nav items (Home / TV Guide / Recordings)
- [x] TV Guide EPG grid — proportional-width cells, synced horizontal scroll, gap-filling, live/focused highlights
- [x] Info panel above EPG — channel icon, title, duration, description updates on focus
- [x] Live preview in sidebar — muted ExoPlayer, auto-starts on first channel load
- [x] Full-screen player — ExoPlayer with Digest auth, navigates from EPG on OK press
- [x] Sky Q colour palette — measured from screenshots: panel `#0028D3`, main bg `#0128CC`
- [x] Sidebar panel layout — 23% screen width, 1.5% left margin (percentage-based via `BoxWithConstraints`)
- [x] Video preview — edge-to-edge within panel, no padding
- [x] Sidebar right-edge glass border — 13dp wide, two gradient overlays drawn **after** `drawContent()` so they composite over the video:
  - White specular: `0%→9.8%→0.5%→0%` white, peak at strip 3 (light catching the glass curve)
  - Black shadow: `0%→0%→5%→20%` black, starts mid-border (surface curving away from light)
  - Alphas reverse-engineered from measured pixel values using `α = (result_G − base_G) / (255 − base_G)`; right strips darker than base confirmed shadow not specular
- [x] Content area left-edge drop shadow — 15dp, `37%→26%→13%→11%` black gradient; panel casts shadow onto content. Drawn after `drawContent()` in AppShell content `Box`

---

## Next steps (priority order)

### 1. Full-screen player polish
- Mini-guide overlay: press DOWN while watching to slide up a single-row channel strip with current + next programme
- Channel up/down via D-pad while in full screen (no need to go back to EPG)
- Show programme title + time remaining as a brief OSD on channel change
- Back button returns to EPG (already works via nav back stack)

### 2. EPG navigation improvements
- Auto-scroll EPG to current time on load (horizontal scroll position)
- "Now" indicator — vertical line showing current time position in the grid
- Press OK on a future programme → show "Set reminder / Record" dialog
- Press OK on a currently-airing programme → go to full-screen player (done) but also update sidebar preview to that channel

### 3. Sidebar preview — follow EPG focus
- As user D-pads through channels in the EPG, update the sidebar preview to that channel after a short debounce (~800ms) so it feels responsive without hammering stream switches

### 4. Recordings screen
- TVHeadend DVR API: `GET /api/dvr/entry/grid`
- List upcoming + completed recordings
- Press OK → play recording (`/dvrfile/{id}` stream)
- Schedule a recording from the EPG (future programme → record option)

### 5. Home screen
- "On now" horizontal row — currently airing programmes across all channels, pulled from EPG state already loaded
- Hero banner — featured currently-airing programme (e.g. highest-number channel that has a live event)
- No on-demand/streaming content needed

### 6. Visual polish
- Channel logos in EPG channel column (replace text with `AsyncImage`)
- Programme progress bar on currently-airing cells (thin bar at bottom showing % elapsed)
- Smooth scroll animation when EPG loads to position current time
- Focus ring style — match Sky Q's white border highlight more closely
- Sidebar collapse animation when entering full-screen player

### 7. Sideload & test on real hardware
- Build release APK, sideload to Google TV dongle
- Test D-pad navigation end-to-end
- Tune `DP_PER_MINUTE` and row heights for 1080p TV display
- Check stream latency / buffering on local network
