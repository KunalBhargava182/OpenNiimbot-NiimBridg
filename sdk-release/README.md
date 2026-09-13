# NiimBot Print Sticker SDK — Integration Guide

Built 2026-07-28 from the NiimBridge repo. This is everything a developer needs
to add the "Print Sticker" screen to another app — no need to look at the
NiimBridge source at all.

---

## What's in this folder

| File | What it is |
|---|---|
| `niimbot-driver-release.aar` | The NIIMBOT D110_M protocol driver (Bluetooth transport, packet framing, print sequencing). |
| `niimbot-print-sdk-release.aar` | The print-sticker screen itself, built on top of the driver. |
| `README.md` | This guide. |

**Both `.aar` files are required.** The SDK depends on the driver as a
separate artifact — without it, the app won't build.

## What this SDK does, in one paragraph

You give it a Study ID and an EMIR ID. It shows one screen that connects to
the paired NIIMBOT printer (remembering it for next time), previews a fixed
label template, lets the coordinator pick 1 or 6 copies, prints, and hands
you back a success/failure result. Nothing about the label (font, layout,
rotation, density) is configurable from outside — that's intentional, it's
locked to what's already been validated against the hardware.

---

## Quick start (TL;DR)

1. Copy both `.aar` files into `app/libs/`.
2. Add the dependency block below to `app/build.gradle.kts`.
3. Make sure `minSdk` is 26 or higher.
4. Call `NiimbotPrintSdk.createIntent(context, studyId, emirId)` and launch it.
5. Read the result extra for success/failure.

Full detail on each step below.

---

## Step 1 — Copy the files in

Copy both AARs into your app module's `libs/` folder (create it if it doesn't exist):

```
app/libs/niimbot-driver-release.aar
app/libs/niimbot-print-sdk-release.aar
```

## Step 2 — Add the Gradle dependencies

### If your app uses Kotlin DSL (`app/build.gradle.kts`)

```kotlin
android {
    defaultConfig {
        minSdk = 26   // required by the SDK — raise this if it's currently lower
    }
}

dependencies {
    implementation(files("libs/niimbot-driver-release.aar"))
    implementation(files("libs/niimbot-print-sdk-release.aar"))

    // The SDK needs these. If your app already has them (it almost certainly
    // does), Gradle just resolves to whichever version is newer — these are
    // floors, not exact pins, so there's no need to match them precisely.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### If your app uses Groovy (`app/build.gradle`)

```groovy
android {
    defaultConfig {
        minSdk 26   // required by the SDK — raise this if it's currently lower
    }
}

dependencies {
    implementation files('libs/niimbot-driver-release.aar')
    implementation files('libs/niimbot-print-sdk-release.aar')

    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.10.0'
    implementation 'androidx.core:core-ktx:1.10.1'
    implementation 'androidx.activity:activity-ktx:1.8.0'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0'
}
```

No repository changes needed — `implementation(files(...))` reads the `.aar`
directly off disk, and the library versions above resolve from Maven
Central/Google, which every Android project already has configured.

### Permissions — nothing to do

`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, and the legacy pre-Android-12
equivalents are already declared inside the AARs' manifests and merge into
your app automatically. **Do not add them to your own `AndroidManifest.xml`.**
The SDK requests them at runtime itself, with its own explanation UI, the
first time its screen opens.

## Step 3 — Sync and build

Sync Gradle. If you see a manifest merge error about `minSdkVersion`, see
[Troubleshooting](#troubleshooting) below.

---

## Step 4 — Launch the screen

Study ID and EMIR ID come from your backend and are shown **read-only** in the
preview — there's no text entry in this screen, so no typo risk. The only
thing the coordinator picks is 1 or 6 copies, then taps Print.

### Recommended: modern Activity Result API

```kotlin
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import com.muse.niimbot.sdk.NiimbotPrintSdk

class YourFragmentOrActivity {

    private val printStickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val message = result.data?.getStringExtra(NiimbotPrintSdk.EXTRA_RESULT_MESSAGE)
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    // Sticker printed successfully.
                }
                else -> {
                    // User backed out, or the print failed. `message` has details either way.
                }
            }
        }

    private fun onPrintStickerClicked(studyId: String, emirId: String) {
        val intent = NiimbotPrintSdk.createIntent(requireContext(), studyId, emirId)
        printStickerLauncher.launch(intent)
    }
}
```

### Alternative: classic `startActivityForResult`

Use this if your codebase hasn't adopted the Activity Result API yet.

```kotlin
import android.app.Activity
import android.content.Intent
import com.muse.niimbot.sdk.NiimbotPrintSdk

private const val REQUEST_PRINT_STICKER = 4210

private fun onPrintStickerClicked(studyId: String, emirId: String) {
    val intent = NiimbotPrintSdk.createIntent(this, studyId, emirId)
    startActivityForResult(intent, REQUEST_PRINT_STICKER)
}

override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQUEST_PRINT_STICKER) return

    val message = data?.getStringExtra(NiimbotPrintSdk.EXTRA_RESULT_MESSAGE)
    when (resultCode) {
        Activity.RESULT_OK -> {
            // Sticker printed successfully.
        }
        Activity.RESULT_CANCELED -> {
            // User backed out, or the print failed. `message` has details either way.
        }
    }
}
```

---

## Full working example

A complete, self-contained fragment with a button — copy/paste and adjust
`studyId`/`emirId` to however you fetch them from your backend.

```kotlin
package com.example.studyapp.print

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.muse.niimbot.sdk.NiimbotPrintSdk

class PrintStickerLauncherFragment : Fragment() {

    private val printStickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val message = result.data?.getStringExtra(NiimbotPrintSdk.EXTRA_RESULT_MESSAGE)
            val text = if (result.resultCode == Activity.RESULT_OK) {
                "Sticker printed"
            } else {
                "Print failed: ${message ?: "unknown error"}"
            }
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return Button(requireContext()).apply {
            text = "Print Sticker"
            setOnClickListener {
                // Replace these with the real values from your backend/session.
                val studyId = fetchStudyIdFromBackend()
                val emirId = fetchEmirIdFromBackend()
                val intent = NiimbotPrintSdk.createIntent(requireContext(), studyId, emirId)
                printStickerLauncher.launch(intent)
            }
        }
    }

    private fun fetchStudyIdFromBackend(): String = TODO("wire up to your backend/session data")
    private fun fetchEmirIdFromBackend(): String = TODO("wire up to your backend/session data")
}
```

---

## What the coordinator sees, end to end

1. **First launch only:** a permission prompt for Bluetooth (the SDK explains
   why before asking).
2. If a printer was used before on this device, it **auto-connects** — no tap
   needed. Otherwise, a plain list of currently *paired* Bluetooth devices is
   shown to pick from.
3. Once connected: the fixed label preview, with Study ID/EMIR ID filled in
   from what you passed in, and a **1 / 6** copies choice.
4. Tap **Print**. A progress indicator shows while it prints.
5. Success or failure is shown on-screen, with a **Done** button that returns
   control to your app with the result.

**One-time setup requirement:** the printer must already be *paired* in
Android's system Bluetooth settings before it will show up in the SDK's
device list — this is a Bluetooth Classic (SPP) requirement, not something
any app can do programmatically. The screen has an "Open Bluetooth settings"
shortcut for this if needed.

---

## API reference

Everything you need for the packaged screen lives in one object:
`com.muse.niimbot.sdk.NiimbotPrintSdk`. (If you need your own screen instead of this
one, see [Building a fully custom print screen](#building-a-fully-custom-print-screen-headless-api)
further down — a separate, additive API for that case.)

| Member | Type | Meaning |
|---|---|---|
| `createIntent(context, studyId, emirId)` | `fun … : Intent` | Builds the launch `Intent`. Both IDs are required and shown read-only. |
| `EXTRA_STUDY_ID` | `String` constant | Intent extra key used internally — you don't need this unless building the Intent manually. |
| `EXTRA_EMIR_ID` | `String` constant | Same, for EMIR ID. |
| `EXTRA_RESULT_MESSAGE` | `String` constant | Key to read from the **result** Intent's extras — a human-readable summary on success, or an error message on failure. |

**Result codes** (standard `Activity` result codes, nothing custom):

| `resultCode` | Meaning |
|---|---|
| `Activity.RESULT_OK` | Sticker printed and confirmed by the printer's own page counter. |
| `Activity.RESULT_CANCELED` | Either the user backed out, missing/blank Study ID or EMIR ID was passed in, or the print failed. Check `EXTRA_RESULT_MESSAGE` to tell these apart. |

**What's fixed and cannot be changed from outside the SDK:** sticker
template (3-line ICF layout), font (Monospace, Bold, centered), rotation
(90°), and density (4). Only the copies count (1 or 6) is exposed. This is
intentional — these values are the ones already validated against the actual
printer hardware.

---

## Troubleshooting

**`Manifest merger failed … uses-sdk:minSdkVersion XX cannot be smaller than version 26 declared in library`**
Your app's `minSdk` is below 26. Raise it in `defaultConfig` as shown in Step 2.

**`Duplicate class … found in modules …`**
Your app already includes one of the common libraries (AppCompat, Material,
core-ktx, etc.) at a different version, and something's forcing an exact
duplicate JAR. This is rare with `implementation(...)` — if it happens, remove
the version pin you added in Step 2 for that one library and let Gradle use
your app's existing version instead.

**The printer never shows up in the device list**
It has to be paired in Android's Bluetooth settings first — SPP requires
bonding before an app can connect. Use the "Open Bluetooth settings" shortcut
on the screen, pair the printer there, then come back.

**Nothing happens / permission dialog never appears**
Make sure you're launching the returned `Intent` from an `Activity` or
`Fragment` context, and that you haven't already denied the Bluetooth
permission with "Don't ask again" — in that case Android won't show the
prompt again and the user needs to grant it manually from your app's system
Settings page.

**Print starts but the result never resolves**
Check `EXTRA_RESULT_MESSAGE` on failure — the SDK never reports success
without the printer's own page counter confirming completion, so a genuine
hang usually means the printer ran out of paper, the paper door opened
mid-print, or it went out of Bluetooth range. All of these come back as
`RESULT_CANCELED` with a descriptive message, never a false success.

---

## Building a fully custom print screen (headless API)

Everything above gets you the packaged screen as-is — fastest path, zero UI work,
locked-down template. If your screen needs its own layout, branding, or flow (e.g. an
existing multi-step form that the print step has to live inside of), use the headless
API instead: the same driver, printer, and validated label rendering, with no bundled UI
at all. You build the screen; we hand you the primitives.

Both `.aar` files are still required — the headless API lives in the same two artifacts,
no extra dependency.

### What you get

| Class | Lives in | Purpose |
|---|---|---|
| `NiimbotPrinterConnector` | `niimbot-driver` (`com.muse.niimbot`) | Finds and connects to the paired printer: tries the last-used device first, falls back to any other paired NIIMBOT/D110-named device, skips ones that are paired but currently unreachable (powered off, out of range) instead of giving up on the first one tried. |
| `NiimbotPrinter` | `niimbot-driver` (`com.muse.niimbot`) | Low-level printer session: `connect()`, `disconnect()`, `printBitmap(...)`, `readRfid()`, `readDeviceInfo()`, and a `state: StateFlow<PrinterState>`. |
| `NiimbotLabelRenderer` | `niimbot-print-sdk` (`com.muse.niimbot.sdk`) | Produces the exact same validated label bitmap the packaged screen prints — same font, size, rotation, positions. Not a re-derivation of those constants, the same implementation. |
| `NiimbotPrintSession` | `niimbot-print-sdk` (`com.muse.niimbot.sdk`) | Convenience wrapper bundling the three above behind one small coroutine API, for the common case. Use this unless you need finer control (e.g. custom device selection logic), in which case use `NiimbotPrinterConnector`/`NiimbotPrinter` directly instead. |

### Quick start

```kotlin
import com.muse.niimbot.sdk.NiimbotPrintSession
import kotlinx.coroutines.launch

class YourPrintFragment : Fragment() {

    private lateinit var session: NiimbotPrintSession

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = NiimbotPrintSession(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            session.state.collect { state ->
                // render your own UI for Disconnected / Connecting / Ready / Printing / Failed
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { session.connect() }
                .onFailure { /* show your own error UI, e.g. "no paired printer found" */ }
        }
    }

    private fun onPrintTapped(studyId: String, emirId: String, copies: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val remaining = runCatching { session.labelsRemaining() }.getOrNull()
            if (remaining != null && remaining < copies) {
                // block and tell the user to replace the roll -- do this before printing, not after
                return@launch
            }
            val result = runCatching { session.printLabel(studyId, emirId, copies) }
            // result.getOrNull()?.complete tells you if the printer's own page counter
            // confirmed all `copies` -- never assume success without checking this
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        session.disconnect()
    }
}
```

### API reference

| Member | Type | Meaning |
|---|---|---|
| `NiimbotPrintSession(context)` | constructor | Creates a session. Does not connect yet. Holds an `applicationContext` internally, safe to construct from a Fragment/Activity without leaking it. |
| `state` | `StateFlow<PrinterState>` | `Disconnected` / `Connecting` / `Ready(DeviceInfo)` / `Printing(page, total, percent)` / `Failed(reason)`. Drive your own UI off this. |
| `connect()` | `suspend fun ... : DeviceInfo` | Connects using `NiimbotPrinterConnector`'s last-used-then-fallback logic. Throws `NiimbotException` if no paired candidate could be reached. |
| `labelsRemaining()` | `suspend fun ... : Int` | Reads the roll's remaining label count via RFID. Call this before printing so you can block a batch that can't finish. Throws if not connected. |
| `printLabel(studyId, emirId, copies = 1)` | `suspend fun ... : PrintResult` | Renders the label via `NiimbotLabelRenderer` and prints `copies` identical copies. `PrintResult.complete` is `true` only when the printer's own page counter confirms all copies — check it, don't assume. Throws if not connected. |
| `disconnect()` | `fun` | Releases the connection and ends the session for good. Call from `onCleared()`/`onDestroyView()`, not `onPause()` — a rotation shouldn't drop a live print. The session cannot be reused afterward; construct a new `NiimbotPrintSession` to reconnect later. |

On `copies`: `printLabel(studyId, emirId, copies = 6)` prints all 6 as one page-counter-
confirmed transaction — this is the recommended, validated path for N identical copies.
If you need per-copy retry granularity (e.g. to report "4 of 6 succeeded, retry the other
2" instead of an all-or-nothing result), call `printLabel(studyId, emirId, copies = 1)` in
a loop instead and track results yourself — but that means N separate hardware
transactions instead of one. The same tradeoff applies if you call
`NiimbotPrinter.printBitmap(...)` directly instead of going through `NiimbotPrintSession`.

On device selection: `NiimbotPrinterConnector` (used internally by `NiimbotPrintSession.
connect()`) tries the last-successfully-connected device first, then falls through every
other bonded NIIMBOT/D110-named device, skipping ones that fail to connect instead of
throwing on the first one tried. This matters because `BluetoothAdapter.
getBondedDevices()` reflects pairing, not reachability — a paired-but-powered-off printer
still appears in that set. If you need to reproduce or customize this logic yourself
(different naming match, different persistence, BLE instead of SPP), construct
`NiimbotPrinterConnector` directly, or implement its `LastDeviceStore` interface to swap
in your own persistence (a database, a sync-backed preference) instead of the default
`SharedPreferences`-backed one.

### Troubleshooting additions

**`connect()` throws even though a printer is paired and powered on**
Check that it's actually named recognizably (contains "NIIMBOT" or "D110") in Android's
paired devices list — `NiimbotPrinterConnector` matches on name. If it's paired under a
different name, connect directly with `NiimbotPrinter`/`SppTransport` yourself instead of
the connector.

**`printLabel`'s result reports fewer copies than requested**
This is not a bug to work around — it means the printer's page counter didn't confirm
every copy (paper ran out, door opened, went out of range mid-batch). Surface
`PrintResult.pagesConfirmed` (and `PrintResult.pagesRequested`) to the user rather than
assuming success.

**I don't need a custom screen after all**
Use `NiimbotPrintSdk.createIntent(...)` instead — it's simpler and still fully supported.
The headless API is only worth the extra UI work if you need a screen the packaged one
can't give you.

---

## Rebuilding these AARs (for us, not for you)

If the NiimBridge team ships an update, the commands to regenerate both files
from source are:

```
./gradlew :niimbot-driver:assembleRelease :niimbot-print-sdk:assembleRelease
```

Outputs land in `niimbot-driver/build/outputs/aar/` and
`niimbot-print-sdk/build/outputs/aar/`.
