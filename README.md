 <h1>grupetto: workout data for Gen 2 Peloton Bikes <img width="60" align="left" alt="Dougmeister" src="https://user-images.githubusercontent.com/17497673/192138426-4d96996a-738e-43a2-895e-4204ca11ed6d.png">
</h1>

&nbsp;

**grupetto** is an app that creates a system-wide overlay with live statistics about your ride:

<p align="center">
<img width="500" alt="Dougmeister" src="https://user-images.githubusercontent.com/17497673/192136344-e649bfd8-1d8b-452c-ab5f-9aff84118e25.gif">
</p>

You can use it to watch media from sources like Netflix and Youtube while viewing your current 
*power output, cadence, resistance, and speed.*

***Note: This project is wholly unaffiliated with Peloton. Please do not approach them for support
with this app. It relies on undocumented interfaces that are subject to change with any update.***

---

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

Sideload onto the bike over ADB (see the upstream sideloading guide below):

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

---

For developers: When forking this project, be sure to update
the [ReleaseChecker](app/src/main/java/com/spop/poverlay/releases/ReleaseChecker.kt) to point to
your repository

---

## Upstream grupetto documentation

The sections below are the original grupetto docs, retained for reference.

- [Installation](#installation)
- [Usage](#usage)
- [Implementation](#implementation)
    * [Getting access to sensor data](#getting-access-to-sensor-data)
        + [Exploring SerialPort](#exploring-serialport)
        + [Establishing system service connection](#establishing-system-service-connection)
    * [Android App Architecture](#android-app-architecture)
    * [Other hardware](#other-hardware)
- [Reporting Issues](#reporting-issues)
- [Unimplemented features](#unimplemented-features)
- [Naming](#whats-with-the-name)

# Installation

grupetto must be side-loaded onto the Peloton,
follow [this guide](https://www.reddit.com/r/pelotoncycle/wiki/index/howto/root/) to learn how to
sideload an APK.

After following those steps, the APK for grupetto can be found on the Releases tab above.

Note: Unfortunately, sideloading functionality was recently locked behind a valid subscription. At
the time of writing, once the overlay has been installed its continued function is not tied to a
subscription. (this is subject to change)

# Usage

- When first run, grupetto will ask for permission to draw over other apps. This permission is
  required for the app to function.

<p align="center">
<img width="639" alt="Permissions Screenshot" src="https://user-images.githubusercontent.com/17497673/192136649-ebbda631-c5d6-4233-a8d0-b7573a8d8250.png">
</p>

- By default the overlay is shown in its _expanded_ form.

  Pressing and holding on power graph will shrink it, and the overlay be dragged around the screen
  to position it:

<p align="center">
<img width="400" alt="Example of Dragging Overlay" src="https://user-images.githubusercontent.com/17497673/192136764-2f351135-366c-467d-a78d-b5f15de93aee.gif">
</p>

- Clicking once on either edge of the overlay enters _minimized_ mode. In this mode the overlay can
  no longer be relocated, but it continues to show updated values. Click on it again to re-enter the
  exapnded mode

<p align="center">

<img width="270" alt="Minimized Mode Gif" src="https://user-images.githubusercontent.com/17497673/192137812-ae7a4d4d-aa31-4220-90cf-9fe3f9a16ed4.gif">

<img width="270" alt="Minimized Mode Static" src="https://user-images.githubusercontent.com/17497673/192137073-55c4ee53-0897-478e-9e20-18bdf08f2018.png">
</p>

- The timer can only be controlled in _expanded_ mode. Click once on the timer to start it. Click
  again to pause, and hold down for 3 seconds to restart it. There is a configuration option on the
  main screen to show or hide the timer when minimized

<p align="center">
<img width="270" alt="Shows Timer Operation" src="https://user-images.githubusercontent.com/17497673/192137306-66e1d0c9-12a5-49d3-8b2b-6fc2ee4c8d61.gif">
</p>

# Implementation

## Getting access to sensor data

### Exploring SerialPort

This project was greatly helped by the work done by @ihaque on the Pelomon
project: https://ihaque.org/tag/pelomon.html

My initial attempt was to directly interface with the serial port via the SerialPort API (which
would rely on reflection). But the OS is locked down quite extensively, and there was no clear way
for a non-system app to access the serial port directly. Additionally there is no (known) way to get
a system application onto the bike, which heavily limits our options, as many permissions used
internally are marked as system level permissions.

I explored trying to leverage the built-in updater services, but it seems unlikely that there is no
verification of installed packages, and they are also likely behind similar system level permissions
to the serial port.

### Establishing system service connection

After establishing the direct-access method was a non-starter, there I pulled APKs from my
production Generation 2 bike and decompiled them via JADX. They were heavily obfuscated, but using
the Pelomon project as a reference, I was able to track down an internal system service that
provides communication with a Generation 2 bike.

By leveraging that system service, we're able to "leapfrog" the restriction on direct access, since
technically the internal system service is accessing the Serial Port, not our application.

This also provides an ergonomic interface to build against (all the serial port resource handling is
done for us), but _drastically_ increases the odds of this being broken by an update.

---

I also explored a backup approach by using reflection to instantiate internal classes and manipulate
their APIs, but it's a much tricker approach since we're running any code found under the current
app's UID (meaning anything that was locked off to system-apps

## Android App Architecture

Grupetto is an example of implementing a system service with a rich UI. Jetpack Compose was
leveraged for rapid iteration.

A service acts as a `LifecycleOwner` to provide the scaffolding Compose expects outside of a normal
activity (see LifecycleEnabledService.kt)

Due to the nature of this project relying on an undocumented interface that may break without
notice, effort that has been put into organization of the composables themselves is somewhat
limited, and I would not use them as a reference for what a well-built Compose app looks like

However, the patterns used to provide them with data are generally sound, and could be used as a
reference for how a Service can provide data for a complex UI

## Other hardware

The app is intentionally designed to not necessarily rely on data from the Peloton. The app could be
expanded to support other tablets and sensor sources, such as ANT+ power meters.

# Reporting Issues

Please do not approach Peloton with issues related to this. They have no duty to support a reverse
engineered service.

Please use the Github issue tab, and mention which version of the OS you're running if possible (
this is displayed at the bottom of screen before the overlay is started)

# Unimplemented features

I've limited this project's scope due to the fact it could easily be broken by an update.

Features I would consider out-of-scope are mostly related to workout session tracking, and
interfacing directly with other apps.

# What's with the name?

https://en.wikipedia.org/wiki/Autobus_(cycling)
