#!/usr/bin/env python3
"""
HTSP validation script.

Connects to TVHeadend, authenticates, subscribes to the first available
channel, muxes incoming codec frames into MPEG-TS, and saves 10 seconds
to /tmp/test_htsp.ts.

Test the result:
    ffprobe /tmp/test_htsp.ts
    ffplay  /tmp/test_htsp.ts
"""

import socket, struct, hashlib, time, sys, collections

HOST = "192.168.1.7"
PORT = 9982
USER = "emby"
PASS = "emby"
OUT  = "/tmp/test_htsp.ts"
SECS = 10
VER  = 35          # HTSP version to negotiate
PROFILE = "mp2-audio-to-aac-lc"   # server-side transcode profile; None for raw passthrough

# ── HMF codec ────────────────────────────────────────────────────────────────

_MAP, _S64, _STR, _BIN, _LIST = 1, 2, 3, 4, 5

def _decode(buf, o=0, n=None):
    msg = {}
    pos = o
    end = o + (n if n is not None else len(buf))
    while pos + 6 <= end:
        t  = buf[pos]
        nl = buf[pos + 1]
        dl = struct.unpack_from(">I", buf, pos + 2)[0]
        pos += 6
        name = buf[pos:pos + nl].decode("utf-8") if nl else ""
        pos += nl
        d    = buf[pos:pos + dl]
        if name:
            if t == _S64:
                v = 0
                for i in range(dl - 1, -1, -1):
                    v = (v << 8) | d[i]
                msg[name] = v
            elif t == _STR:  msg[name] = d.decode("utf-8")
            elif t == _BIN:  msg[name] = bytes(d)
            elif t == _MAP:  msg[name] = _decode(buf, pos, dl)
            elif t == _LIST: msg[name] = _decode_list(buf, pos, dl)
        pos += dl
    return msg

def _decode_list(buf, o, n):
    items, pos, end = [], o, o + n
    while pos + 6 <= end:
        t  = buf[pos]
        nl = buf[pos + 1]
        dl = struct.unpack_from(">I", buf, pos + 2)[0]
        pos += 6 + nl
        if t == _MAP:
            items.append(_decode(buf, pos, dl))
        pos += dl
    return items

def _encode_s64(v):
    data = bytearray()
    u = v & 0xFFFFFFFFFFFFFFFF
    while u:
        data.append(u & 0xFF)
        u >>= 8
    return bytes(data)

def _encode(msg):
    fields = bytearray()
    for name, value in msg.items():
        nb = name.encode("utf-8")
        if isinstance(value, int):
            d, t = _encode_s64(value), _S64
        elif isinstance(value, str):
            d, t = value.encode("utf-8"), _STR
        elif isinstance(value, (bytes, bytearray)):
            d, t = bytes(value), _BIN
        else:
            continue
        fields += bytes([t, len(nb)]) + struct.pack(">I", len(d)) + nb + d
    return struct.pack(">I", len(fields)) + bytes(fields)

# ── MPEG-TS muxer ────────────────────────────────────────────────────────────

_PID_PAT   = 0x0000
_PID_PMT   = 0x0100
_PID_VIDEO = 0x0101
_PID_AUDIO = 0x0102

_ST = {"H264": 0x1B, "HEVC": 0x24, "H265": 0x24,
       "AAC": 0x0F, "MP2": 0x04, "MPEG2AUDIO": 0x04,
       "AC3": 0x81, "EAC3": 0x87}

_CRC_TABLE = None

def _build_crc_table():
    t = []
    for i in range(256):
        c = i << 24
        for _ in range(8):
            c = (((c << 1) ^ 0x04C11DB7) if c & 0x80000000 else (c << 1)) & 0xFFFFFFFF
        t.append(c)
    return t

def _crc32(data):
    global _CRC_TABLE
    if _CRC_TABLE is None:
        _CRC_TABLE = _build_crc_table()
    crc = 0xFFFFFFFF
    for b in data:
        crc = ((crc << 8) ^ _CRC_TABLE[((crc >> 24) ^ b) & 0xFF]) & 0xFFFFFFFF
    return struct.pack(">I", crc)

def _encode_pts(v, marker):
    return bytes([
        (marker | ((v >> 29) & 0x0E) | 0x01) & 0xFF,
        (v >> 22) & 0xFF,
        (((v >> 14) & 0xFE) | 0x01) & 0xFF,
        (v >> 7) & 0xFF,
        (((v << 1) & 0xFE) | 0x01) & 0xFF,
    ])

def _build_pcr(pts):
    b = pts & 0x1FFFFFFFF
    return bytes([
        0x10,
        (b >> 25) & 0xFF,
        (b >> 17) & 0xFF,
        (b >>  9) & 0xFF,
        (b >>  1) & 0xFF,
        (((b & 1) << 7) | 0x7E) & 0xFF,
        0x00,
    ])

class TsMuxer:
    def __init__(self, out):
        self.out = out
        self.streams = {}          # htsp_index -> dict
        self.cc = collections.defaultdict(int)
        self.ready = False

    def init(self, htsp_streams):
        self.streams.clear()
        video_ok = audio_ok = False
        for s in htsp_streams:
            idx = s.get("index")
            typ = s.get("type", "").upper()
            if idx is None or typ not in _ST:
                continue
            is_video = typ in ("H264", "HEVC", "H265")
            if is_video and not video_ok:
                self.streams[idx] = {"pid": _PID_VIDEO, "st": _ST[typ], "is_video": True}
                video_ok = True
            elif not is_video and not audio_ok:
                info = {"pid": _PID_AUDIO, "st": _ST[typ], "is_video": False}
                if typ == "AAC":
                    meta = s.get("meta", b"")
                    b0 = meta[0] if meta else 0
                    b1 = meta[1] if len(meta) > 1 else 0
                    info["ap"] = max(2, (b0 >> 3) & 0x1F)
                    info["ai"] = max(0, min(12, ((b0 & 7) << 1) | (b1 >> 7)))
                    info["ac"] = max(1, (b1 >> 3) & 0xF)
                self.streams[idx] = info
                audio_ok = True
        self.ready = len(self.streams) > 0
        print(f"  streams: { {i: s['st'] for i,s in self.streams.items()} }")
        return self.ready

    def mux(self, idx, pts, dts, payload, is_key):
        info = self.streams.get(idx)
        if not info:
            return
        if not self.ready or (info["is_video"] and is_key):
            self._write_pat()
            self._write_pmt()
        if info["st"] == 0x0F:          # AAC → wrap in ADTS
            payload = self._adts(info, payload)
        self._write_pes(info, pts, dts, payload, is_key)

    def _write_pat(self):
        s = bytes([0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00,
                   0x00, 0x01,
                   0xE0 | (_PID_PMT >> 8), _PID_PMT & 0xFF])
        self._ts(_PID_PAT, True, None, b"\x00" + s + _crc32(s))

    def _write_pmt(self):
        vid = next((s for s in self.streams.values() if     s["is_video"]), None)
        aud = next((s for s in self.streams.values() if not s["is_video"]), None)
        pcr = (vid or aud or {"pid": _PID_VIDEO})["pid"]
        ents = b""
        for s in [x for x in [vid, aud] if x]:
            ents += bytes([s["st"], 0xE0 | (s["pid"] >> 8), s["pid"] & 0xFF, 0xF0, 0x00])
        sl = 9 + len(ents) + 4
        hdr = bytes([0x02, 0xB0 | (sl >> 8), sl & 0xFF,
                     0x00, 0x01, 0xC1, 0x00, 0x00,
                     0xE0 | (pcr >> 8), pcr & 0xFF,
                     0xF0, 0x00]) + ents
        self._ts(_PID_PMT, True, None, b"\x00" + hdr + _crc32(hdr))

    def _write_pes(self, info, pts, dts, data, is_key):
        sid = 0xE0 if info["is_video"] else 0xC0
        hd = info["is_video"] and dts != pts and dts > 0
        fl = 0xC0 if hd else 0x80
        ol = 10 if hd else 5
        bl = 3 + ol + len(data)
        pl = 0 if info["is_video"] else bl
        pt = _encode_pts(pts, 0x30 if hd else 0x20)
        dt = _encode_pts(dts, 0x10) if hd else b""
        hdr = bytes([0x00, 0x00, 0x01, sid,
                     (pl >> 8) & 0xFF, pl & 0xFF,
                     0x80, fl, ol]) + pt + dt
        af = _build_pcr(pts) if (is_key and info["is_video"]) else None
        af_sz = (1 + len(af)) if af else 0
        stream = hdr + data
        first = min(184 - af_sz, len(stream))
        self._ts(info["pid"], True, af, stream[:first])
        off = first
        while off < len(stream):
            end = min(off + 184, len(stream))
            self._ts(info["pid"], False, None, stream[off:end])
            off = end

    def _adts(self, info, payload):
        total = len(payload) + 7
        p, sr, ch = info.get("ap", 2), info.get("ai", 3), info.get("ac", 2)
        return bytes([
            0xFF, 0xF1,
            (((p - 1) & 3) << 6) | ((sr & 0xF) << 2) | ((ch >> 2) & 1),
            ((ch & 3) << 6) | ((total >> 11) & 3),
            (total >> 3) & 0xFF,
            ((total & 7) << 5) | 0x1F,
            0xFC,
        ]) + payload

    def _ts(self, pid, pusi, af, payload):
        pkt = bytearray(188)
        p = 0
        pkt[p] = 0x47; p += 1
        pkt[p] = (0x40 if pusi else 0) | ((pid >> 8) & 0x1F); p += 1
        pkt[p] = pid & 0xFF; p += 1
        c = self.cc[pid] & 0x0F
        self.cc[pid] = (c + 1) & 0x0F
        afc = (0x30 if af is not None and payload else 0x20 if af is not None else 0x10)
        pkt[p] = afc | c; p += 1
        if af is not None:
            pkt[p] = len(af); p += 1
            pkt[p:p + len(af)] = af; p += len(af)
        pay = min(len(payload), 188 - p)
        pkt[p:p + pay] = payload[:pay]; p += pay
        while p < 188:
            pkt[p] = 0xFF; p += 1
        self.out.write(bytes(pkt))

# ── HTSP client ───────────────────────────────────────────────────────────────

class HtspClient:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=15)
        self.seq  = 1
        self._buf: collections.deque = collections.deque()

    def _recv_one(self):
        """Read exactly one HMF message from the wire."""
        raw = self._recvn(4)
        length = struct.unpack(">I", raw)[0]
        body = self._recvn(length)
        return _decode(bytearray(body))

    def _recvn(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise EOFError("HTSP connection closed")
            data += chunk
        return data

    def _send(self, msg):
        self.sock.sendall(_encode(msg))

    def rpc(self, msg):
        """Send a message and return the matching response, buffering any events."""
        sid = self.seq; self.seq += 1
        msg["seq"] = sid
        self._send(msg)
        while True:
            m = self._recv_one()
            if m.get("seq") == sid:
                return m
            self._buf.append(m)     # buffer any events that arrive first

    def recv(self):
        """Return the next event (buffered or from wire)."""
        if self._buf:
            return self._buf.popleft()
        return self._recv_one()

    def send(self, msg):
        self._send(msg)

    def close(self):
        self.sock.close()

# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    print(f"Connecting to {HOST}:{PORT} …")
    c = HtspClient(HOST, PORT)

    # Handshake
    hello = c.rpc({"method": "hello", "clientname": "htsp-test", "htspversion": VER})
    print(f"Hello: server={hello.get('servername')}  htspver={hello.get('htspversion')}")
    challenge = hello.get("challenge", b"")
    digest = hashlib.sha1(PASS.encode() + challenge).digest()
    auth = c.rpc({"method": "authenticate", "htspversion": VER,
                  "username": USER, "digest": digest})
    print(f"Auth: noaccess={auth.get('noaccess', 0)}  keys={list(auth.keys())}")
    if auth.get("noaccess", 0):
        print("ERROR: auth rejected"); sys.exit(1)

    # Channel list via enableAsyncMetadata
    print("Getting channel list …")
    channels = {}
    c.rpc({"method": "enableAsyncMetadata", "epg": 0})   # response is empty/unimportant
    while True:
        msg = c.recv()
        m   = msg.get("method", "")
        if m == "channelAdd":
            cid = msg.get("channelId")
            if cid is not None:
                channels[cid] = msg.get("channelName", "?")
        elif m == "initialSyncCompleted":
            print(f"  {len(channels)} channels found")
            break

    if not channels:
        print("No channels found"); sys.exit(1)

    # Pick first channel alphabetically by name
    cid, cname = sorted(channels.items(), key=lambda x: x[1])[0]
    print(f"Subscribing to channel {cid}: {cname}")

    sub_id = 99
    sub_msg = {
        "method": "subscribe",
        "subscriptionId": sub_id,
        "channelId": cid,
        "90khz": 1,
        "weight": 150,
        "queueDepth": 5_000_000,
    }
    if PROFILE:
        sub_msg["profile"] = PROFILE
    print(f"Profile: {PROFILE or '(raw passthrough)'}")
    resp = c.rpc(sub_msg)
    print(f"Subscribe: {list(resp.keys())}  noaccess={resp.get('noaccess', 0)}")

    # Receive and mux
    c.sock.settimeout(5)
    with open(OUT, "wb") as f:
        muxer = TsMuxer(f)
        start = None
        pkts  = 0
        wrote = 0
        print(f"Recording {SECS}s to {OUT} …")

        while True:
            try:
                msg = c.recv()
            except socket.timeout:
                if start and time.time() - start >= SECS:
                    break
                continue
            except EOFError:
                print("Connection closed by server"); break

            m = msg.get("method", "")

            if m == "subscriptionStart":
                streams = msg.get("streams", [])
                print(f"subscriptionStart: {len(streams)} streams")
                for s in streams:
                    print(f"  idx={s.get('index')}  type={s.get('type')}  "
                          f"width={s.get('width','')}  height={s.get('height','')}")
                if not muxer.init(streams):
                    print("ERROR: no usable streams"); break
                start = time.time()

            elif m == "muxpkt":
                if not start:
                    continue
                if msg.get("subscriptionId") != sub_id:
                    continue
                idx     = msg.get("stream")
                pts     = msg.get("pts", 0)
                dts     = msg.get("dts", pts)
                payload = msg.get("payload", b"")
                is_key  = msg.get("frametype") == 0x49   # 'I' = 73

                if idx is not None and payload:
                    before = f.tell()
                    muxer.mux(idx, pts, dts, payload, is_key)
                    f.flush()
                    wrote += f.tell() - before
                    pkts  += 1
                    if pkts % 100 == 0:
                        print(f"  {time.time()-start:.1f}s  pkts={pkts}  written={wrote//1024}KB")

                if time.time() - start >= SECS:
                    break

            elif m == "subscriptionStop":
                print(f"subscriptionStop: {msg.get('status')}"); break

    c.send({"method": "unsubscribe", "subscriptionId": sub_id})
    c.close()

    import os
    size = os.path.getsize(OUT)
    print(f"\nDone: {OUT}  ({size//1024} KB)")
    print("Validate with:")
    print(f"  ffprobe -v quiet -show_streams {OUT}")
    print(f"  ffplay {OUT}")

if __name__ == "__main__":
    main()
