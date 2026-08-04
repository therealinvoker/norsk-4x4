# Norsk 4×4 (this fork)

This is a fork of [grupetto](https://github.com/selalipop/grupetto) that turns the live overlay into
a coach for the **Norwegian 4×4** interval protocol
([NTNU/CERG](https://www.ntnu.edu/cerg/advice)): a warm-up, then repeats of a hard work interval and
an active recovery, then a cool-down.

Intensity in the 4×4 is driven and measured by **power (watts)**. The fork also supports a standard
**Bluetooth heart-rate strap**: pair one and your live HR shows on the banner, the minimized strip,
and in workout history — and if you enter your age, the banner draws a target HR zone during work
intervals.

> It installs under its own application id (`com.trudys.norsk4x4`), so it **coexists** with the
> original grupetto app — both can be on the bike at once.

## What this fork adds

- **4×4 interval engine** — a pure-Kotlin state machine (`fourbyfour/`) that derives the current
  phase, countdown, and progress purely from elapsed time. Configurable warm-up / work / recovery /
  cool-down durations and interval count, with optional **power targets** for effort color-coding.
- **Live 4×4 banner**, integrated onto the **same surface** as the power/cadence/resistance/speed
  stats (one panel, not a second floating window). Shows the phase, a big countdown, the next phase,
  and a progress bar.
- **Best-interval pacing line** — during each work interval the banner plots your **live output**
  against a dashed reference line at your **best-ever interval average** (with a green/amber/red
  running interval average). Falls back to a cumulative **kJ vs. best** chip outside work intervals.
- **Minimized strip** keeps a compact 4×4 field (phase + countdown) inline with the stats, so the
  interval info never disappears when you tap away.
- **Workout history** — every session is saved as JSON on the device (`history/`); a history screen
  lists rides and a detail screen shows stats plus a power-over-time chart with the work intervals
  shaded and your best ride drawn as a ghost line.
- **Bluetooth heart-rate strap** — scan for and connect a standard BLE HR strap from the config
  screen; the app remembers your last strap and **auto-reconnects** on the next ride. Live HR is
  shown on the banner, the minimized strip, and in workout history.
- **HR target zone** — enter your age and, during each work interval, the banner overlays a live
  heart-rate trace against a shaded **85–95% HRmax** band (Tanaka estimate, `208 − 0.7 × age`),
  drawn side-by-side with the power pacing chart.
- **Backpedal compensation** — pedaling backward during recovery reports real cadence but ~0 W on
  the Bike+ load cell, which would otherwise flatline power, speed, and the graph. The app detects
  the backpedal signature (cadence present, ~0 power) and synthesizes a forward-equivalent power
  from cadence + resistance, so a backward recovery spin behaves like forward pedaling everywhere —
  gauge, graph, speed, and recorded output.
- **Auto-stop & save** — if a running session sits idle (no pedaling, or paused) for 15 minutes it
  is automatically stopped and saved.
- **Haptic cues** on phase changes (toggleable), and a **vibrate-on-phase-change** setting.
- Banner controls: **tap ❚❚ to pause** — this stops the clock but keeps the 4×4 on screen — then
  **▶ resume** or **↻ reset** (restart from warm-up, discarding the current ride); **long-press to
  stop** and save the session.

## Building & installing

The app builds with **JDK 17** and the Android SDK (compileSdk 33). With a local command-line SDK:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # or your JDK 17 path
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Sideload onto the bike over ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.trudys.norsk4x4 -c android.intent.category.LAUNCHER 1
```

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) also builds the debug APK and uploads
it as an artifact on pushes to the feature branch / `develop` and on manual dispatch.

## Using it

Open **Norsk 4×4**, set your durations / intervals / (optional) power targets on the config screen,
then tap **Start 4×4 ride**. The overlay coaches you through the protocol; pull up **Workout
history** any time to review past sessions and compare against your best.
