# Streaming TVHeadend over HTSP into ExoPlayer

How this app pulls live TV from **TVHeadend** over the **HTSP** protocol, re-muxes the
raw codec frames into **MPEG-TS**, and plays them with **Media3/ExoPlayer** on Android TV.

This is written for the next person trying to do the same thing. HTSP is barely
documented outside the TVHeadend source tree, and the ExoPlayer side has several
non-obvious failure modes that each look like "black screen" or "no sound." Everything
below was reverse-engineered from TVHeadend's `htsmsg_binary.c` / `htsp_server.c` and
about four hours of on-device debugging. Treat the TVHeadend source as the spec — there
is no official wire-format document.

---

## 1. The big picture

```
TVHeadend (192.168.1.7)
  ├── HTTP API  :9981   (digest auth) — used for channel list, EPG, icons
  └── HTSP      :9982   (binary)      — used for live video/audio frames

App
  HtspConnection ──TCP──> :9982
       │  (HMF binary messages: hello, authenticate, subscribe, muxpkt …)
       ▼
  HtspDataSource (a Media3 DataSource)
       │  receives raw codec frames (H.264 NALUs, AAC/MP2 frames)
       ▼
  TsMuxer  ── wraps frames into a 188-byte MPEG-TS elementary stream
       │
       ▼  PipedOutputStream → PipedInputStream
  ExoPlayer (ProgressiveMediaSource + DefaultExtractorsFactory)
       │  TsExtractor → H264Reader / AAC reader → MediaCodec
       ▼
  Surface / TextureView   +   AudioTrack
```

**Why re-mux to MPEG-TS?** HTSP does *not* give you a container. It gives you
individual codec access units (one H.264 picture, one AAC frame, …) with timestamps.
ExoPlayer needs a parseable byte stream. MPEG-TS is the easiest container to synthesize
on the fly: it's a flat sequence of fixed 188-byte packets, no global header, no seek
table, no "finalize" step — perfect for an unbounded live stream fed through a pipe.

### Key files

| File | Role |
|------|------|
| `data/htsp/HtspMessage.kt`     | HMF (HTSP Message Format) binary codec — encode/decode |
| `data/htsp/HtspConnection.kt`  | TCP socket, handshake, request/response + async event flow |
| `data/htsp/HtspDataSource.kt`  | Media3 `DataSource`: subscribes, drives the muxer, exposes a pipe |
| `data/htsp/TsMuxer.kt`         | Raw codec frames → MPEG-TS (PAT/PMT/PES/PCR/ADTS) |
| `ui/video/VideoViewModel.kt`   | Builds the ExoPlayer, channel-ID map, debounced channel switching |

---

## 2. HTSP protocol

HTSP = "Home TV Streaming Protocol." TCP, default port **9982**. All messages use a
binary serialization called **HMF**.

### 2.1 HMF wire format

A message is a length-prefixed bag of named fields:

```
Message:  [body-length : uint32 big-endian] [field]*

Field:    [type : 1 byte]
          [namelen : 1 byte]
          [datalen : 4 bytes BIG-endian]     ← NOTE: datalen comes BEFORE the name
          [name : namelen bytes, UTF-8]
          [data : datalen bytes]
```

Field types:

| Type | Value | Encoding |
|------|-------|----------|
| MAP  | 1 | nested fields (a sub-message) |
| S64  | 2 | **variable-length little-endian** signed int |
| STR  | 3 | UTF-8 string |
| BIN  | 4 | raw bytes |
| LIST | 5 | nested fields, each with `namelen = 0` (positional) |

Two things that will bite you:

- **`datalen` is encoded before `name`** even though `name` is laid out first
  conceptually. Read length first, then the name bytes, then the data bytes.
- **S64 is little-endian and variable length.** The integer is stored with the *minimum*
  number of bytes, least-significant first. **The value `0` is encoded as zero bytes**
  (datalen = 0). Decoding: `for i in (datalen-1) downTo 0: v = (v << 8) | bytes[i]`.

LIST entries are just fields with an empty name; iterate positionally.

See `HtspMessage.kt` for the exact ~120-line codec. It mirrors TVHeadend's
`htsmsg_binary.c` — if your decode is off by a few bytes, that's the file to diff against.

### 2.2 Connection framing

Each message on the wire is `[uint32 length][length bytes of HMF body]`. The read loop is:

```
length = readInt()             // 4-byte big-endian
if (length <= 0 || length > 20_000_000) bail   // sanity bound
body   = readFully(length)
msg    = HtspMessage.parse(body)
dispatch(msg)
```

### 2.3 Handshake

1. **`hello`** — client sends `{ method: "hello", clientname, htspversion }`.
   - We request `htspversion = 35`; the server (v44 here) replies with its own version,
     `servername`, and a **`challenge`** (32 random bytes, type BIN).
2. **`authenticate`** — `{ method: "authenticate", htspversion, username, digest }`
   - `digest = SHA1(password_bytes ++ challenge_bytes)`. Raw 20-byte SHA-1, sent as BIN.
   - **Not** HTTP digest, **not** the same scheme as the :9981 HTTP API (which uses real
     HTTP Digest auth). Two different auth mechanisms on the two ports — don't mix them up.
   - Response: if `noaccess != 0`, you were rejected.

> **Access control gotcha:** if the TCP connection is *accepted then immediately closed*
> right after `hello` (0-byte reply), it's almost always a server-side access-control
> problem, not your code: HTSP disabled, or the user lacks "streaming" permission, or an
> IP allowlist excludes the client. Check Configuration → Access Control.

### 2.4 Request / response vs. async events

HTSP multiplexes two kinds of traffic on the one socket:

- **RPC**: you put a `seq` (S64) on a request; the matching response echoes the same
  `seq`. We use a `ConcurrentHashMap<Int, CompletableDeferred>` keyed by seq, with a 10s
  timeout.
- **Async events**: `subscriptionStart`, `muxpkt`, `subscriptionStop`, `channelAdd`, …
  arrive **with no `seq`** at any time. We publish these to a `MutableSharedFlow`.

The dispatcher: if a message has a `seq` that matches a pending request, complete that
deferred; otherwise emit it as an async event.

> ⚠️ **Race trap (cost us real time):** see Issue #2. Async events for a subscription can
> arrive *before* the subscribe RPC "returns," so you must be listening to the event flow
> **before** you send `subscribe`.

### 2.5 Getting the channel list (`enableAsyncMetadata`)

The HTTP API identifies channels by **UUID string**; HTSP identifies them by a **numeric
`channelId`**. You need both (UUID for icons/EPG via HTTP, numeric ID to subscribe), so
build a map at startup:

1. Send `{ method: "enableAsyncMetadata", epg: 0 }`.
2. The server streams a burst of **`channelAdd`** events, each with `channelId` (numeric)
   and `channelIdStr` (the UUID), then a single **`initialSyncCompleted`**.
3. Collect `channelIdStr → channelId` into a map; stop at `initialSyncCompleted`.

Again: subscribe to the event flow *before* sending `enableAsyncMetadata`, or you'll miss
early `channelAdd`s (no replay buffer on a `SharedFlow`). We use Kotlin's
`.onSubscription { … }` to send the trigger only once the collector is attached.

### 2.6 Subscribing to a channel

```
{ method: "subscribe",
  subscriptionId: <client-chosen int>,   // you pick it; echoed on every muxpkt
  channelId:      <numeric id>,
  profile:        "mp2-audio-to-aac-lc",  // optional stream profile (see Issue #4)
  90khz:          1,                       // timestamps in 90 kHz units, not µs
  queueDepth:     5000000,
  weight:         150 }
```

Then the server pushes async events:

- **`subscriptionStart`** — once, up front. Contains a `streams` LIST. Each entry:
  `index` (the per-stream id used by muxpkts), `type` ("H264", "HEVC", "MPEG2AUDIO",
  "AAC", "AC3", "EAC3", "TELETEXT", "PCR", …), and for video `width`/`height`, and for
  audio sometimes `meta` (the codec config, e.g. AAC AudioSpecificConfig). **A channel
  typically has several audio streams and extra PCR/teletext/subtitle "streams" — pick
  the first usable video and the first usable audio and ignore the rest.**
- **`muxpkt`** — one per access unit, streamed continuously:
  - `subscriptionId` (filter on yours), `stream` (matches a `subscriptionStart` index),
    `pts`, `dts` (90 kHz), `frametype` (ASCII: `'I'`=73=`0x49`, `'P'`, `'B'`), and
    `payload` (BIN: the raw codec bytes — for H.264 these are Annex-B NAL units with
    `00 00 01` start codes).
- **`subscriptionStop`** — teardown.

Unsubscribe with `{ method: "unsubscribe", subscriptionId }`.

> `90khz: 1` matters. Without it TVHeadend sends microsecond timestamps; MPEG-TS PTS/DTS
> are defined in 90 kHz, so requesting 90 kHz lets you pass the values straight through.

### 2.7 Stream profiles & transcoding

By default a subscription uses the **`pass`** profile: original codecs, untouched. If the
client can't decode something (very common: broadcast **MPEG-1 Layer II audio**), use a
server-side **transcoding stream profile**.

- Profiles live in TVHeadend under **Configuration → Stream → Stream Profiles** (a shared
  list — *not* under any "HTSP" page, which is why it looks missing).
- Transcoding requires a libav-enabled build. Check
  `GET /api/serverinfo` → `capabilities` contains `"libav"`.
- A profile that copies video and only re-encodes audio is cheap and safe:
  `pro_vcodec = copy`, `pro_acodec = webtv-aac-lc`, `src_acodec = [MPEG2AUDIO]`.
- **The client opts in by name**: put `profile: "<name>"` in the `subscribe` message.
  Existence of the profile on the server does nothing on its own.
- You can enumerate profiles via `GET /api/profile/list` (digest auth).

### 2.8 Timeshift / pause-live (bonus)

If you subscribe with a `timeshiftPeriod`, TVHeadend buffers the stream server-side and
you can pause/rewind live TV by sending:

- `{ method: "subscriptionSpeed", subscriptionId, speed }` — `0` = pause, `100` = 1×.
- `subscriptionSkip` to seek within the buffer.

`HtspController` in `HtspDataSource.kt` wraps this and tracks "seconds behind live" from
the muxpkt PTS. (Not required for plain live playback.)

---

## 3. The bridge: HTSP frames → MPEG-TS → ExoPlayer

### 3.1 Custom `DataSource` + a pipe

ExoPlayer pulls bytes through a `DataSource`. `HtspDataSource`:

- `open()`: opens the HTSP connection, subscribes, and starts a coroutine that turns
  incoming `muxpkt`s into MPEG-TS via `TsMuxer`, writing into a `PipedOutputStream`.
  Returns `C.LENGTH_UNSET` (live, unknown length).
- `read()`: reads from the paired `PipedInputStream` (blocks until data; returns
  `C.RESULT_END_OF_INPUT` at EOF). Pipe buffer is 512 KB.
- The player's media-source factory:
  `ProgressiveMediaSource.Factory(htspFactory, DefaultExtractorsFactory())`.
  ExoPlayer sniffs the piped bytes, picks `TsExtractor`, and decodes.

URI scheme is just `htsp:///<numericChannelId>`; `open()` parses the path.

### 3.2 The MPEG-TS muxer (`TsMuxer.kt`)

Minimal but complete. Emits a continuous stream of 188-byte packets:

- **PIDs**: PAT `0x0000`, PMT `0x0100`, video `0x0101`, audio `0x0102`.
- **PAT/PMT**: written on the first packet and re-emitted on every video keyframe so a
  late-joining parser can sync. PMT lists video + one audio elementary stream with the
  right `stream_type` byte (H.264 `0x1B`, HEVC `0x24`, AAC/ADTS `0x0F`, MP2 `0x04`,
  AC-3 `0x81`, E-AC-3 `0x87`). CRC-32/MPEG-2 over each section.
- **PES**: one PES packet per access unit. Video uses unbounded length (`0`) so large
  keyframes can span many TS packets; audio uses the real length. PTS (and DTS when it
  differs, i.e. B-frames) encoded in the standard 33-bit/90 kHz layout.
- **PCR**: written in the adaptation field of each video keyframe's first TS packet.
- **Continuity counter** per PID, incremented on every payload-bearing packet.
- **ADTS**: AAC needs an ADTS header *unless the source already provides one* — see
  Issue #4.

If a `.ts` dump of this muxer plays in `ffprobe`/`ffmpeg`, the muxer is correct; the bug
is elsewhere (extractor, decoder, or the live/pipe path). This was the single most useful
diagnostic — see §4.

---

## 4. Issues & solutions (the painful part)

All four below presented as either "black screen" or "no sound," and none had an obvious
error message. Listed in the order we hit them.

### Issue 1 — Connection closes right after `hello`

**Symptom:** TCP connects, you send `hello`, server closes the socket (0-byte reply).
**Cause:** TVHeadend access control, not your code. HTSP disabled, or the user lacks the
*streaming* permission, or an IP allowlist.
**Fix:** Configuration → Access Control: enable HTSP + streaming for the user/network.
**Tell-tale:** the :9981 HTTP API works fine for the same user while :9982 won't even
complete a handshake.

### Issue 2 — `subscriptionStart` dropped (race), muxer never initialises

**Symptom:** subscribe "succeeds" but no video ever decodes; `muxer.init()` never runs, so
every `muxpkt` is dropped.
**Cause:** async events have no `seq` and start arriving the instant TVHeadend processes
`subscribe` — which can be *before* your `subscribe` RPC returns and *before* you start
collecting the event flow. A `MutableSharedFlow` with no replay silently drops anything
emitted while there's no collector.
**Fix:** attach the collector first, then send `subscribe` from inside
`flow.onSubscription { … }`, guaranteeing you're listening before the trigger goes out.
Same pattern for `enableAsyncMetadata`.

### Issue 3 — Black screen on live, but the same bytes play fine from a file 🩸

This was the four-hour one. Worth understanding in full because it's a real ExoPlayer
limitation, not a bug in your code.

**Symptom:** video connects, ExoPlayer reads megabytes continuously, the H.264 decoder is
even created and configured (correct resolution) — but `player.bufferedPosition` stays
**0**, state never leaves `BUFFERING`, screen stays black with a spinner forever.

**What threw us off:** a `.ts` dump of the exact bytes played perfectly in ffmpeg *and*
in ExoPlayer when loaded from a local file via `DefaultDataSource`. So "the muxer is fine"
and "ExoPlayer can parse it" were both true — yet live playback failed.

**Root cause:** This broadcast stream contains **zero IDR frames**. It uses *non-IDR
I-frames + recovery-point SEI* for random access, which is completely normal for DVB.
ExoPlayer's TS `H264Reader` marks a sample as a keyframe (`BUFFER_FLAG_KEY_FRAME`) **only
when it sees an IDR NAL (type 5)**. With no IDRs, *no* video sample is ever flagged as a
sync sample. A **continuous/live** source can't begin playback without a sync sample to
start from, so the buffered duration never advances past 0 and it never reaches `READY`.

**Why the file worked anyway:** a *finite* source hits `loadingFinished`, which
short-circuits ExoPlayer's buffered-position logic to "end of source" and forces `READY`
— masking the missing keyframe. Only the unbounded live path exposes it. (And ffmpeg
infers keyframes from the recovery-point SEI; ExoPlayer does not.)

**Fix (one line in the muxer):** set the **`random_access_indicator`** bit in the TS
adaptation field on keyframe packets. ExoPlayer's `H264Reader` honours that flag and will
mark the sample as a keyframe even without an IDR. In `TsMuxer.buildPcr()` the
adaptation-field flags byte goes from `0x10` (PCR only) to **`0x50`** (`0x40`
random_access_indicator | `0x10` PCR). We already only emit that adaptation field on
keyframes (HTSP `frametype == 'I'`), so it lands in exactly the right place.

**How we proved it:** wrapped the `ExtractorsFactory` to log every `sampleMetadata` call —
all video samples had `flags=0`. Then counted NAL types in the dump
(`grep`-for-`00 00 01 xx` in Python): `IDR(5): 0, non-IDR I-slice(1): 771,
SEI(6): 753`. Smoking gun.

### Issue 4 — No sound (MP2 audio), then garbled silence (double ADTS)

**Symptom A:** video works, no audio. ExoPlayer logs the audio track as
`audio/mpeg-L2 (sel=false)` — the track selector dropped it because the device has no
MPEG-1 **Layer II** decoder (Android mandates MP3/Layer III, not Layer II).
**Fix A:** transcode audio to AAC-LC server-side via a stream profile and request it by
name (`profile: "mp2-audio-to-aac-lc"` in `subscribe`). Video stays `copy`. See §2.7.

**Symptom B:** after enabling the profile, the AAC decoder runs but spews
`C2SoftAacDec: Invalid AAC stream` (error `0x0005`) and substitutes silence.
**Cause:** TVHeadend's transcoded AAC arrives over HTSP **already ADTS-framed** (payload
starts `FF F1 …`, and `subscriptionStart` gives `meta = null`). Our muxer always added its
*own* ADTS header → two stacked headers → the decoder reads a bogus frame length.
**Fix B:** only ADTS-wrap *raw* AAC. Detect an existing header
(`payload[0]==0xFF && (payload[1] & 0xF6)==0xF0`) and pass those frames through untouched.
Raw AAC (`meta` present, no `FF Fx` prefix) still gets wrapped using the
AudioSpecificConfig.

---

## 5. Gotchas checklist for implementers

- **HMF**: `datalen` precedes `name`; S64 is little-endian variable length; `0` = 0 bytes.
- **Two auth schemes**: HTSP digest = `SHA1(password ++ challenge)`; the HTTP API uses
  real HTTP Digest. Different ports, different mechanisms.
- **Listen before you ask**: attach the event collector before sending `subscribe` /
  `enableAsyncMetadata`, or you lose `subscriptionStart` / early `channelAdd`s.
- **Map UUID ↔ numeric channelId**: HTTP uses UUIDs, HTSP uses numeric ids.
- **Request `90khz: 1`** so timestamps are already in MPEG-TS units.
- **A channel has many streams**: multiple audio tracks, PCR, teletext, subtitles. Select
  one video + one audio; drop the rest.
- **Keyframes**: if your broadcaster uses non-IDR I-frames (most DVB does), set the TS
  `random_access_indicator` on keyframe packets or live playback never starts in ExoPlayer.
- **AAC**: don't double-wrap ADTS. Detect `FF Fx`.
- **MP2 audio** is undecodable on most Android devices — transcode to AAC-LC server-side.
- **Diagnosis workflow that works**: dump the muxer output to a `.ts` file and probe it
  with `ffprobe`/`ffmpeg`. If it's valid there, the muxer is fine — look at the live/pipe
  path, sync samples, and decoder support next. Wrapping the `ExtractorsFactory` to log
  `sampleMetadata` flags, and counting NAL types in Python, were the two decisive tools.

---

## 6. Reference: message shapes we actually use

```
// handshake
-> { method:"hello", clientname:"SkyQ", htspversion:35 }
<- { servername, htspversion, challenge:<32 bytes>, ... }
-> { method:"authenticate", htspversion:35, username, digest:SHA1(pass++challenge) }
<- { noaccess:0|1, ... }

// channel list
-> { method:"enableAsyncMetadata", epg:0 }
<- { method:"channelAdd", channelId:<int>, channelIdStr:<uuid>, channelName, ... }   (xN)
<- { method:"initialSyncCompleted" }

// playback
-> { method:"subscribe", subscriptionId, channelId, profile?, 90khz:1, queueDepth, weight }
<- { method:"subscriptionStart", streams:[ {index,type,width?,height?,meta?}, ... ] }
<- { method:"muxpkt", subscriptionId, stream, pts, dts, frametype, payload:<bytes> }   (stream)
<- { method:"subscriptionStop", status? }
-> { method:"unsubscribe", subscriptionId }

// timeshift (optional)
-> { method:"subscriptionSpeed", subscriptionId, speed:0|100 }
```

Versions this was verified against: TVHeadend HTSP **v44** (we negotiate v35),
Media3/ExoPlayer **1.3.1**, on a Chromecast HD (Google TV, Amlogic). Field availability
and behaviour can vary across TVHeadend versions — when in doubt, read the source.
