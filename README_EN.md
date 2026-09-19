# ChannelsGuard · 视频号守门员

> **One line: tap a Channels (视频号) link and watch it — swipe for the next one and you get kicked out.**
>
> A tiny Android accessibility service for "I only meant to watch one, then lost two hours".

No root, no Xposed, no WeChat modification, no network, no runtime permissions.

🌐 [简体中文](./README.md) | English

---

## Contents

- [Why](#why)
- [Features](#features)
- [Quick start](#quick-start)
- [Usage](#usage)
- [How it works](#how-it-works)
- [Known limitations](#known-limitations)
- [FAQ](#faq)
- [Build it yourself](#build-it-yourself)
- [Privacy](#privacy)
- [Roadmap](#roadmap)
- [License](#license)

---

## Why

WeChat has no "only allow this one video" switch. Even with the Channels entry hidden from the Discover page, links sent by friends or posted in groups are still the most convenient way in: you tap, you watch, you swipe up — and you're in the recommendation feed.

This tool targets exactly that one gesture: **it breaks the automatic chain of "watch this one → swipe to the next"**, without getting in the way of actually watching.

---

## Features

| Feature | Description |
| --- | --- |
| Strict mode | Swipe to change video inside Channels → exits immediately, back to the chat |
| Commitment period | **1 day / 7 days / 2 weeks / 1 month / 6 months** — once confirmed, the mode cannot be changed inside the app until it expires |
| Timed mode | Watch freely for 5 / 10 / 20 minutes; reminder at 1 minute left; auto-exit when time's up |
| Paused | Temporarily disable blocking without turning off the system accessibility service |
| Multiple links in a row | Tapping different Channels links in a chat works normally — **only swiping exits** |
| Adjustable sensitivity | Trigger the exit after 1 swipe (aggressive) / 2 (recommended) / 4 (relaxed) |
| Back-action fallback | Some ROMs swallow the back action; it is re-sent up to three times |
| Blocking stats | Blocks today, total blocks, days guarded |
| Debug log | Last 30 events, **one-tap copy** — so you can paste the log when reporting a problem |

---

## Quick start

### 1. Download and install

Grab the latest APK (e.g. `ChannelsGuard-v2.3-debug.apk`) from [Releases](https://github.com/coopermary952-ship-it/ChannelsGuard/releases), transfer it to your phone and install it.

> Requires Android 8.0 (API 26) or higher. Allow "install from unknown sources" when prompted.

### 2. Enable the accessibility service (the only required step)

Open ChannelsGuard → tap **Go to accessibility settings** → find **ChannelsGuard** in the list → turn it on.

The path varies slightly by manufacturer, usually:

```
Settings → Accessibility → Installed services / Downloaded apps → ChannelsGuard → On
```

Come back to the app; the top should read **Status: running ✔**.

### 3. Pick a mode

- Want results now → **Strict mode**, and choose a commitment period
- Prefer a daily quota over a hard cut → **Timed mode**, 10 minutes

Done. Just use WeChat as usual; it watches in the background.

---

## Usage

### Which mode should I pick?

| Mode | For | Behaviour |
| --- | --- | --- |
| **Strict** | Already hooked; want it cut off now | Swipe to change video → exit immediately. Each video must be opened fresh from its link |
| **Timed** | Want less, but not zero | Countdown starts on entering Channels; swipe freely; reminder at 1 minute left; auto-exit at zero |
| **Paused** | Temporarily need to browse | No blocking at all — the service stays on so you can switch back instantly |

### About the commitment period

Strict mode requires choosing a period first: **1 day / 7 days / 2 weeks / 1 month / 6 months**. After confirming:

- **Timed mode** and **Paused** become greyed out and untappable
- The top of the screen shows **"Strict mode locked, X days X hours until you can change it"**
- It unlocks automatically when the countdown ends

This is not just a UI trick: even if you bypass the interface and edit the local config directly, the service re-evaluates on every event and still enforces strict mode while the commitment is active.

> ⚠️ Honest boundary: it locks **this app's own settings only**. You can still work around it by turning off the accessibility service, uninstalling the app, or clearing app data. The tool handles your reflex; the final line of defence is yours.

### Layout, top to bottom

1. **Service status** — whether it's enabled in the system
2. **Go to accessibility settings** — one-tap jump to system settings
3. **Stats** — blocks today / total blocks / days guarded
4. **Lock notice** — remaining commitment time (hidden when not locked)
5. **Guard mode** — the three modes
6. **Strict commitment period** — shown when Strict is selected and not yet locked
7. **Timed duration** — shown when Timed is selected
8. **Sensitivity** — shown in Strict mode; how many swipes count as "you're scrolling"
9. **Content-change detection** — experimental fallback, off by default
10. **Debug log** — last 30 events + a **Copy debug log** button

### Three steps when it fails to block

1. Open the **Debug log** at the bottom and look for lines like `滑动 ΔY=… 第n次` (`swipe ΔY=… #n`).
2. **Log exists but no exit** → events do arrive, just below threshold. Set sensitivity to **1 (aggressive)**.
3. **No log at all** → your WeChat build doesn't emit scroll events. Enable **Content-change detection** (experimental): it periodically compares the visible text on screen and exits when the text as a whole changes.

> The 12-hour and 3-day options were removed on request — **the shortest commitment is now 1 day**.

---

## How it works

Everything runs through the standard `AccessibilityService`. Nothing is injected or hooked.

```
                  ┌──────────────────────────────────────┐
  you swipe in    │  WeChat window event                 │
  WeChat Channels │  (typeWindowStateChanged)            │
               →  │  class name contains "finder"        │
                  │  → judged to be the Channels screen  │
                  └──────────────┬───────────────────────┘
                                 │
                  ┌──────────────▼───────────────────────┐
                  │  scroll event (typeViewScrolled)     │
                  │  within 2s of entering → ignored     │
                  │  cumulative count ≥ sensitivity      │
                  │  → judged "you're scrolling"         │
                  │  default 2, configurable 1/2/4       │
                  └──────────────┬───────────────────────┘
                                 │
                  ┌──────────────▼───────────────────────┐
                  │  performGlobalAction(                │
                  │      GLOBAL_ACTION_BACK)             │
                  │  re-check at 0.7s / 1.5s, re-send if │
                  │  still inside Channels               │
                  │  + toast + block counter +1          │
                  └──────────────────────────────────────┘
```

Key parameters (all at the top of `GuardService.java`, tweak as you like):

| Parameter | Default | Meaning |
| --- | --- | --- |
| `ENTER_GRACE_MS` | 2000 | Layout/restore scrolls right after entering are ignored |
| `KEY_SENSITIVITY` | 2 | Cumulative swipes needed to trigger exit (configurable in-app) |
| `BLOCK_DEBOUNCE_MS` | 1200 | Minimum gap between two blocks, to avoid exiting repeatedly |
| `BACK_RETRY_DELAYS` | 0 / 700 / 1500 ms | When the back action is re-sent, for ROMs that swallow it |

**Why cumulative counting instead of "4 scrolls within 1 second"?**
v2.1 tightened the rule to "≥4 scrolls inside a 1-second window" to fix a false positive. On real devices that never holds: one swipe emits only 1–2 events, and several seconds of watching between swipes reset the counter every time — so scrolling through five or six videos triggered nothing.

v2.3 instead **counts cumulatively after entering and exits once the threshold is hit** (default 2, adjustable). The 2-second entry grace is kept, so WeChat's single restore-scroll when you open a second link still doesn't trigger a false exit.

---

## Known limitations

All real and unsolved — stated up front:

1. **Fast scrolling in the comments area triggers a false exit**
   Strict mode does not yet distinguish the main feed from the comment panel. Fixing it requires capturing `event.getSource()` class names screen by screen on a real device.

2. **The timed-mode countdown can be killed by the system**
   It runs on a `Handler` inside the service; if the process is killed by battery optimisation it restarts the countdown. A proper fix needs a foreground service plus `AlarmManager`.

3. **It may break after a major WeChat update**
   "Class name contains `finder`" is WeChat's internal implementation with no public guarantee. If it stops working one day, the in-app debug log tells you whether it failed to *enter* or failed to *block*.

4. **It cannot stop deliberate circumvention**
   Turning off the accessibility service, uninstalling, or clearing app data all defeat it. That's an Android boundary, not a bug in this app.

5. **Only affects package `com.tencent.mm` (WeChat)**
   Other short-video apps are not whitelisted, and are not planned.

6. **The experimental content-change detection can misfire**
   It compares visible text to decide whether the video changed, so a like-count tick or comments loading can cause a false exit. It is a fallback, off by default, recommended only after you confirm no scroll events arrive.

---

## FAQ

**Q: Will it affect other WeChat features?**
A: The service subscribes to only two event types (window state changes and view scrolls) from one package, and intervenes only on screens judged to be Channels. Likes, typing comments and pausing playback emit no scroll events, so they never trigger a block — though fast scrolling in comments can be misjudged (see limitation 1).

**Q: Could it get my account banned?**
A: In normal mode it reads no on-screen text, modifies no WeChat data and injects no code: it reads public system window/scroll events and calls the standard system "back" action, the same principle as common accessibility utilities. With the experimental feature on, it additionally reads the visible text of the current screen for comparison — still no chat data, still no network. Accessibility tools carry platform-policy uncertainty; judge the risk yourself and use it only on your own device.

**Q: I scrolled through five or six videos and nothing happened.**
A: Run the [three steps](#three-steps-when-it-fails-to-block) above, starting from the debug log. Setting sensitivity to "1 (aggressive)" fixes it in most cases.

**Q: Can it cap my total daily watch time?**
A: Not yet. It can cap a single session and forbid swiping entirely. A daily quota is on the roadmap.

**Q: Why isn't my usage data uploaded anywhere?**
A: Because there is no networking at all — see Privacy below.

**Q: New phone or reinstall — do stats survive?**
A: They live in local `SharedPreferences`, and `allowBackup` is off in the manifest, so uninstalling clears them.

---

## Build it yourself

Requirements: **Android 8.0+ device**, **JDK 17+**, **Android SDK**.

```bash
git clone https://github.com/coopermary952-ship-it/ChannelsGuard.git
cd ChannelsGuard

# point local.properties at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew assembleDebug
```

Output lands in `app/build/outputs/apk/debug/`, and a versioned copy is written to `dist/ChannelsGuard-v<version>-debug.apk`.

Project layout:

```
ChannelsGuard/
├── app/src/main/
│   ├── java/com/xingyuan/channelsguard/
│   │   ├── GuardService.java   # screen detection, swipe recognition, blocking, stats
│   │   └── MainActivity.java   # status, mode, commitment, sensitivity, log
│   └── res/xml/accessibility_service_config.xml  # subscription config
└── dist/                       # build output (gitignored)
```

---

## Privacy

- **No network**: no network permission in the manifest, no network calls in the code — uploading is physically impossible.
- **No screen text by default**: regular blocking only reads the *system-level signal* that a scroll happened. No text is read.
- **⚠️ The only exception, and it's off by default**: with the experimental **Content-change detection** enabled, the app walks the visible text of the current screen every 800 ms and builds a fingerprint **in memory** to compare against the previous one. The fingerprint is never written to disk, never stored in `SharedPreferences`, and never sent anywhere. If you don't tick that box, that code never runs.
- **WeChat only**: the accessibility config hard-codes `android:packageNames="com.tencent.mm"`; events from other apps are not received at all.
- **Local data only**: mode, commitment, block stats and debug log live in the app's private `SharedPreferences`, with `allowBackup="false"` — clearing on uninstall.
- **No third-party SDKs**: depends on the Android SDK alone. No analytics, no push, no ads.

---

## Roadmap

- [ ] Tell the main feed apart from the comment area (needs per-screen class-name capture on a real device)
- [ ] Daily total time quota
- [ ] Move the timed countdown to a foreground service + `AlarmManager` so it survives process death
- [ ] A 3-second "calm down" confirmation instead of an immediate exit
- [ ] Weekly view: block-count trend
- [ ] Screenshots in the README

---

## License

[MIT License](LICENSE) — use and modify freely; the author accepts no responsibility for consequences of use.

WeChat and Channels are products of Tencent. This project is not affiliated with them in any way.
