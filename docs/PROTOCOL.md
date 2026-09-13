# NIIMBOT D110_M Protocol — Verified Specification

**Status:** Verified against a live HCI snoop capture of the official NIIMBOT Android app
printing to our own D110_M unit (serial `I205020233`).

**Evidence:** `btsnoop_hci.log`, captured 2026-07-27 ~16:51 IST. 3,454 HCI records → 1,530 L2CAP
PDUs → 1,827 validated NIIMBOT frames across two complete print jobs (job 1 = qty 1, job 2 = qty 2).
Both jobs' 96×320 bitmaps were reconstructed from the capture with 320/320 rows and zero gaps,
confirming the row-encoding decode is complete and correct.

**Confidence key:** ✅ = observed directly in capture · ⚠️ = inferred, needs runtime confirmation

---

## 1. Transport — Bluetooth Classic RFCOMM (SPP) ✅

The official app uses **Bluetooth Classic SPP**, not BLE. The capture contains **no ATT/GATT
traffic whatsoever** (no L2CAP CID 4). Traffic ran over dynamically allocated L2CAP CIDs
(0x51 outbound, 0x41 inbound) carrying RFCOMM.

| Item | Value |
|---|---|
| Profile | Serial Port Profile (SPP) over RFCOMM |
| Service UUID | `00001101-0000-1000-8000-00805F9B34FB` |
| Android API | `BluetoothDevice.createRfcommSocketToServiceRecord()` |
| Prerequisite | Device must be **bonded** (paired in system settings) first |

### BLE fallback ⚠️

Community sources report a BLE transparent-serial service on most NIIMBOT models. Not exercised
by the official app on this unit, so treat as unverified:

| Item | Value |
|---|---|
| Service UUID | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` |
| Characteristic | `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f` (`WRITE_NO_RESPONSE` + `NOTIFY`) |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

Printers expose **two Bluetooth MAC addresses** — one BLE, one Classic — differing by a rotation
of the first three octets (e.g. `26:03:03:C3:F9:11` vs `03:26:03:C3:F9:11`). Never hardcode a MAC.

---

## 2. Frame format ✅

```
0x55 0x55 | type(u8) | len(u8) | data[len] | checksum(u8) | 0xAA 0xAA

checksum = type XOR len XOR data[0] XOR ... XOR data[len-1]
```

Frames arrive **fragmented** across RFCOMM/L2CAP boundaries. A receiver must accumulate bytes,
only parse when `available >= data[3] + 7`, validate the checksum, and resynchronise by scanning
forward to the next `55 55` on failure.

---

## 3. Opcodes ✅

| TX opcode | Name | RX opcode | Seen |
|---|---|---|---|
| `0x01` | PrintStart | `0x02` | ✅ |
| `0x07` | SetRtcClock | `0x08` | ✅ |
| `0x13` | SetPageSize | `0x14` | ✅ |
| `0x1A` | GetRfid | `0x1B` | ✅ |
| `0x21` | SetDensity | `0x31` | ✅ |
| `0x23` | SetLabelType | `0x33` | ✅ |
| `0x40` | GetInfo | `0x40 + key` | ✅ |
| `0x83` | PrintBitmapRowIndexed | — (none) | ✅ |
| `0x84` | PrintEmptyRow | — (none) | ✅ |
| `0x85` | PrintBitmapRow | — (none) | ✅ |
| `0xA3` | PrintStatus | `0xB3` | ✅ |
| `0xAF` | GetCapabilities | `0xBF` | ✅ |
| `0xDC` | Heartbeat | `0xD9` / `0xDE` | ✅ |
| `0xE3` | PageEnd | `0xE4` | ✅ |
| `0xF3` | PrintEnd | `0xF4` | ✅ |

**Never sent by the official app on this model:** `PageStart 0x03`, `PrintClear 0x20`,
`PrintQuantity 0x15`. Do not send them.

**Special inbound types:**

| Type | Meaning | Handling |
|---|---|---|
| `0x00` | Command not supported | Log; not fatal (app triggered it 6× harmlessly) |
| `0xDB` | Printer error | Fatal — abort the job |
| `0xD3` | **Unsolicited** line-progress: `lineIdx(u16BE), 0x01` | Log and ignore; must NOT be matched to a pending request |

---

## 4. Print sequence — `D110M_V4` ✅

Exactly as captured, in order:

```
GetRfid        0x1A [01]                    -> 0x1B  (roll info, optional but app does it)
Heartbeat      0xDC [04]                    -> 0xD9
SetLabelType   0x23 [01]                    -> 0x33 [01]
SetDensity     0x21 [03]                    -> 0x31 [01]
PrintStart     0x01 [9 bytes]               -> 0x02 [01]
PrintStatus    0xA3 [01]                    -> 0xB3 [8 bytes]
SetPageSize    0x13 [13 bytes]              -> 0x14 [01 00]
<image rows>   0x84 / 0x83 / 0x85           -> no responses; 0xD3 arrives unsolicited
PageEnd        0xE3 [01]                    -> 0xE4 [01]
<poll>         0xA3 [01] every ~90 ms       -> 0xB3 until pagesPrinted == totalPages
PrintEnd       0xF3 [01]                    -> 0xF4 [01]
```

### `PrintStart` (0x01) — 9 bytes ✅

| Capture | Job |
|---|---|
| `00 01 00 00 00 00 00 01 00` | qty 1 |
| `00 02 00 00 00 00 00 01 00` | qty 2 |

```
[0:2] totalPages  (u16 BE)
[2:7] 00 00 00 00 00   (reserved; includes pageColor = 0)
[7]   01               (constant, purpose unknown)
[8]   00               (constant)
```

### `SetPageSize` (0x13) — 13 bytes ✅

| Capture | Job |
|---|---|
| `01 40 00 60 00 01 00 00 00 00 00 00 00` | qty 1 |
| `01 40 00 60 00 02 00 00 00 00 00 00 00` | qty 2 |

```
[0:2]  rows   (u16 BE) = 0x0140 = 320   (40 mm × 8 px/mm)
[2:4]  cols   (u16 BE) = 0x0060 = 96    (12 mm × 8 px/mm)
[4:6]  copies (u16 BE) = 1 or 2
[6:13] seven zero bytes
```

**Copies are set here.** There is no separate quantity command.

### `SetLabelType` / `SetDensity` ✅

`SetLabelType 0x23 [01]` — label type 1 (gap labels). `SetDensity 0x21 [03]` — density 3, the
app's default for this model. Range 1–5.

---

## 5. Row encoding ✅

Image is 1-bit, **inverted**: a set bit means a black (printed) pixel. Rows are packed MSB-first,
`ceil(width / 8)` bytes per row — 12 bytes at 96 px.

### The three "count" bytes

Present in both `0x85` and `0x83`. They are the **population count of each third of the row**
(96 px ÷ 3 = 32 px = 4 bytes per third).

Verified against six independent captured rows, e.g. row 199 of job 1:

```
payload  00c7 | 04 06 0c | 01 | 0003c000 07c00001 fff00000
                ^^ ^^ ^^        \--4--/  \--6---/  \--12--/  popcounts
```

niimprint sends zeros here and asserts it works. We compute them properly, matching the official
app. No reason to gamble.

### `PrintEmptyRow` (0x84) ✅

```
[0:2] rowIndex (u16 BE)
[2]   count    (u8)   — number of consecutive blank rows
```
Example: `00 00 46` = 70 blank rows starting at row 0.

### `PrintBitmapRow` (0x85) ✅

```
[0:2]  rowIndex   (u16 BE)
[2:5]  popcounts  (3 × u8)
[5]    repeat     (u8)  — run-length over identical consecutive rows
[6:]   packed row (ceil(width/8) bytes, MSB first)
```

### `PrintBitmapRowIndexed` (0x83) ✅

Used when a row has **fewer than 6** black pixels. Sends X positions instead of a bitmap.
At exactly 6 pixels both encodings occupy 18 bytes, so the app prefers `0x85`.

```
[0:2]  rowIndex   (u16 BE)
[2:5]  popcounts  (3 × u8)
[5]    repeat     (u8)  — run-length; observed up to 7
[6:]   N × u16 BE pixel X positions, where N = sum of popcounts
```
Example: `0046 | 00 05 00 | 01 | 0038 0039 003a 003b 003c` = row 70, five pixels at x = 56–60,
all within the middle third — consistent with popcounts `(0, 5, 0)`.

### Encoder validation ✅

The bitmaps recovered from the capture were re-encoded with the rules above and compared to the
official app's packets: **194/194 and 201/201 packets, byte-identical**. The selection rules and
run-length behaviour are therefore not inferred — they are proven.

---

## 6. `PrintStatus` (0xA3) → `0xB3`, 8 bytes ✅

```
[0:2] pagesPrinted (u16 BE)
[2]   progress1    (u8, 0–100) — page render progress
[3]   progress2    (u8, 0–100) — paper feed progress
[4:6] telemetry    (u16 BE, unknown — varies 0x2265…0x2327 during print) ⚠️
[6]   0x00
[7]   0x00 idle / 0x01 busy
```

### ⚠️ Completion detection — critical

**Completion is `pagesPrinted >= totalPages`. Do NOT use the progress bytes.**

Observed immediately after `PageEnd` on job 1 (`totalPages = 1`):

```
0000 64 64 2265 0001   <- pagesPrinted=0 but progress reads 100/100  (x7 consecutive polls)
0000 00 00 2266 0001   <- progress resets and begins climbing
0000 46 00 22fc 0001
0000 64 5a 2326 0001
0001 64 64 2326 0001   <- DONE: pagesPrinted == totalPages
```

A progress-based check would have reported success seven polls early, on every print. Use the page
counter, poll at ~90 ms, and treat a timeout as **failure**, never as optimistic success.

---

## 7. `GetInfo` (0x40) ✅

Response opcode = `0x40 + key`.

| Key | Name | Captured response | Decoded |
|---|---|---|---|
| `0x07` | AutoShutdown | `03` | 3 |
| `0x08` | **DeviceType** | `09 10` | **2320** = D110_M ✅ |
| `0x0A` | Battery | `03` | 3 |
| `0x0B` | **DeviceSerial** | `49 32 30 35 30 32 30 32 33 33` | ASCII **`I205020233`** |
| `0x0F` | (unknown) | `00 00` | — |

**Note:** niimprint decodes `DeviceSerial` as hex. On this model it is **ASCII**. Decode as ASCII,
fall back to hex if non-printable. The value matches the box label `D110_M` + `I205020233`.

---

## 8. `GetRfid` (0x1A) → `0x1B` ✅

Captured (39 bytes):
```
88 1d bc f9 b3 18 10 80 | 08 | 3031323232323831 | 10 | 505a...3038 | 00ba | 0002 | 01
```

```
[0:8]           uuid (8 bytes)
[8]             barcodeLen        = 0x08
[9 : 9+n]       barcode  (ASCII)  = "01222281"
[+0]            serialLen         = 0x10
[+1 : +1+n]     serial   (ASCII)
then:
  totalLen (u16 BE) = 0x00BA = 186   <- labels on the roll
  usedLen  (u16 BE) = 0x0002 = 2     <- incremented to 3 after one print ✅
  type     (u8)     = 0x01
```

**Remaining labels = totalLen − usedLen.** Surface this in the UI before a 6-sticker batch.

---

## 9. Heartbeat (0xDC) ⚠️

| Request | Response | Captured payload |
|---|---|---|
| `0xDC [04]` | `0xD9`, 11 bytes | `0f 47 03 4d 00 00 01 00 00 00 00` |
| `0xDC [03]` | `0xDE`, 10 bytes | `04 01 04 1e 00 60 02 02 01 00` |

**niimprint has no case for an 11-byte heartbeat** (it handles lengths 20/13/19/10/9 only) and
would throw. Field layout is not confidently decoded — log the raw bytes, do not gate any logic on
it. The app polls `0xDC [04]` roughly once per second while idle.

---

## 10. Optional commands observed ✅

### `SetRtcClock` (0x07) → `0x08 [01 01]`

`01 | 07ea | 07 | 1b | 10 | 33 | 2d` = flag, year 2026 (u16 BE), month 7, day 27, hour 16,
minute 51, second 45. Matches wall-clock time of the capture. Worth sending on connect so printer
logs carry real timestamps.

### `GetCapabilities` (0xAF) → `0xBF`, 90 bytes

TLV list of `key(u8), len(u8), value[len]`. Notable entries:

| Key | Value | Meaning |
|---|---|---|
| `0x0A` | `00 60` = 96 | print width in px ✅ |
| `0x0B` | `06 40` = 1600 | max page length in px (200 mm) |
| `0x0F` | `03` | default density |
| `0x14` | `03` | density-related |

### Ignorable

`0xC1`, `0xA5`, `0x58`, `0x09`, `0x12`, `0x0B`, `0x30`, `0x19` are app-side housekeeping (cloud
key exchange, config probes). Several return `0x00 Not supported`. None are required to print.

---

## 11. Implementation cautions

1. **Bond first.** SPP requires the printer to be paired in Android Bluetooth settings.
2. **Cancel discovery** before opening the RFCOMM socket.
3. **Reassemble fragments.** The Python reference's receive loop does not break on a partial
   packet and will spin — do not copy it.
4. **Tolerate `0xD3`** arriving mid-stream, unmatched to any request.
5. **Assert width ≤ 96 px** before transmitting.
6. **Record `SOFT_VERSION` on every connect.** Firmware drift is the likeliest future breakage.
7. Roughly **0.9 s** of wire time per label at 96×320, plus ~3 s of physical printing.
