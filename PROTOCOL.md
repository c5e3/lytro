# Lytro (1st gen) WiFi protocol — reverse-engineering notes

Camera: Lytro, Inc. · serial A2023xxxxx · HW 1.0a208 · FW v1.2.2
Hotspot SSID `lytro.camera.NNN` (last 3 digits of the serial), camera IP `10.100.1.1`, client gets `10.100.1.x`.

## Ports
| Port | Purpose |
|------|---------|
| 5677 | Push channel. Sends `[HeartbeatTick] tick\r\n\0` on connect (notifications/heartbeat). |
| 5678 | Command channel (framed protocol below). |
| 5679 | Open, silent. Unknown. |

## Framing (port 5678), all little-endian
28-byte header, optionally followed by `length` payload bytes:

```
u32 magic   = 0xFAAA55AF   (bytes AF 55 AA FA)
u32 length  = payload length (request) / response body length (reply)
u32 flags   = request: 0 = command with outgoing payload, 1 = expects data back
              reply:   2 = ack (no data), 3 = data follows
u8[16] cmd  = SCSI-like CDB, same as the USB protocol (zero padded)
```
The camera echoes the header back (same cmd bytes) with reply flags, followed by `length` body bytes.

## Read cycle: load → query → download
1. **Load** `C2 00 <sub>` (flags 0, optional payload): selects what to read. Reply flags 2.
2. **Query** `C6 00` (flags 1, length 4): reply body = u32 size of loaded object. **Empty body = nothing loaded / error.**
3. **Download** `C4 00 00 <u32 LE offset>` (flags 1, length = chunk size): returns `length` bytes starting at `offset` (offset sits at cmd bytes 3–6). 64 KiB chunks work. Without the offset, every request returns the first chunk again.

## Known load subcommands
| Cmd | Payload | Result |
|-----|---------|--------|
| `C2 00 00` | – | Hardware info, 644 bytes (manufacturer, serial, HW rev, FW version, null-padded strings) |
| `C2 00 01` | null-terminated path, e.g. `I:\DCIM\100PHOTO\IMG_0001.RAW` | File contents |
| `C2 00 02` | – | Picture list (format below) |
| `C2 00 03` | – | ⚠️ **REBOOTS THE CAMERA** (sent with no payload). Don't. |
| `C2 00 05` | sha1 id (tried several encodings) | Nothing loaded so far. Format still unknown. |

## Picture list (`C2 00 02`)
Header (92 bytes): `u32 version=1, u32 entry_size=0x80, u32 field_count=10`, then 10 × `(u32 field_id, u32 offset)` describing entry layout.
Then N × 128-byte entries, N = (len − 92) / 128:

| Off | Field |
|-----|-------|
| 0x00 | char[8] folder suffix `PHOTO` |
| 0x08 | char[8] file prefix `IMG_` |
| 0x10 | u32 folder number (100 → `100PHOTO`) |
| 0x14 | u32 file number (1 → `IMG_0001`) |
| 0x18–0x27 | 4 × u32 internal ids (unknown) |
| 0x28 | u32 (0) |
| 0x2C | f32 (e.g. 1.5), probably the focus/lambda |
| 0x30 | char[48] `sha1-<40 hex>` picture id |
| 0x60 | char[28] ISO timestamp `2026-01-30T16:57:15.000Z` |
| 0x7C | u32 (1) |

## Files per picture (`I:\DCIM\<NNN>PHOTO\IMG_<NNNN>.<ext>`)
| Ext | Size | Content |
|-----|------|---------|
| `.TXT` | ~12.7 KB | JSON metadata |
| `.128` | 32768 | 128×128 thumbnail, YUYV 4:2:2 (Y0 U Y1 V) |
| `.RAW` | 16,137,600 | 3280×3280 light-field sensor image, 12-bit packed; ~25 s over WiFi (~630 KB/s) |

## Gotchas
- The camera is fragile: a 65k-port scan knocked the hotspot offline, rapid error+reconnect cycles hang port 5678 for a while, and unknown commands can reboot it. Probe one command at a time, with pauses.
