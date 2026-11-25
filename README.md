# Get After It

A run, ride and workout tracker for the Pebble Time 2, with an Android companion
app that records your activities, draws a live map on the watch and keeps your
history on the phone. No account and no cloud: everything stays on your phone.

## What it does

- **Activities**, picked on the watch or the phone, each with its own screens:
  - **Run**: stats screens only.
  - **Run Track / Hike Track**: live map following a GPX course, with distance to go.
  - **Record Run / Record Hike**: live map with your breadcrumb trail, no course.
  - **Workout**: heart rate and time on the watch only; nothing is recorded on the phone.
- **Recording** on the phone: distance, moving time, current and average pace, and
  climb (from the barometer when the phone has one). Hold Select to pause.
- **Heart rate** from the watch, or from a Bluetooth chest strap (e.g. Polar H10)
  connected directly to the phone.
- **Maps** from OpenStreetMap tiles (downloaded as you go and cached) or an offline
  [OpenAndroMaps](https://www.openandromaps.org/) file, optionally with the Elevate style.
- **Courses**: load a GPX route; the watch shows distance to go and can warn when
  you're off course.
- **History**: every activity is saved as GPX and can be shared. Import your past
  activities from OpenTracks (KMZ/KML/GPX) or any GPX file.

## Requirements

- Pebble Time 2.
- An Android phone (8.0 or later) with the Core Devices Pebble app, paired with the watch.

## Install

1. From the [latest release](../../releases/latest), download the `.apk` and the `.pbw`.
2. Install the `.apk` on your phone. You may need to allow installing apps from your browser
   or file manager. Later releases install over earlier ones and keep your activities.
3. Open the `.pbw` on your phone with the Pebble app to install the watchapp.
4. Open Get After It on the phone and work through the setup list: Bluetooth, location
   ("Allow all the time", since the phone is usually locked during an activity) and
   pairing the watch, which lets the watch start activities while the phone app is closed.

## Using it

On the watch:

- The app opens on a list of activities: Run (stats only), Run Track and Hike Track (map, following
  the course loaded on the phone), Record Run and Record Hike (map, no course) and Workout (heart rate
  and time, not recorded). **Up / Down** choose, **Select** picks. Settings on the phone chooses which appear.
- **Hold Select** to start. Before starting, **Back** returns to the list.

During an activity:

- **Up / Down** switch screens. In Trail mode, **Select** switches between the map and the stats page.
- **Hold Up / Down** zooms the map; **drag** the map to look around and **Back** returns to your position.
- **Hold Select** pauses. While paused, **hold Up for 2 seconds** to stop and save, or press **Down** to resume.

On the phone, Settings chooses units, the fields on each screen, map tiles and the chest
strap. History lists your activities; Import runs reads OpenTracks exports (Settings,
Import/Export, "Export all in one file" as KMZ) or GPX files.

## Building

Everything builds inside the Nix dev shell (`flake.nix`), which provides the Pebble tool,
the Android SDK and a JDK.

```sh
make build       # watchapp: build/*.pbw (installs the pinned Pebble SDK on first run)
make companion   # phone app: companion/app/build/outputs/apk/debug/*.apk
make test        # companion unit tests
```

To install onto a phone connected with adb (USB or wireless debugging):

```sh
ANDROID_SERIAL=<device> make companion-install
make install     # watchapp, through the Pebble app's developer connection
```

`make emu` runs the watchapp in the Pebble emulator. `make companion-release` builds a signed
release APK when `RELEASE_KEYSTORE` and `RELEASE_KEYSTORE_PASSWORD` point at your own key.

## License

[GPL-3.0](LICENSE).
