# Frame

A minimal Android TV home screen (launcher), built for a Nebula Cosmos projector but works on any Android TV / Google TV device.

- Rotating Wikimedia Commons featured photography with place + credit
- Clock, date and weather (Open-Meteo, no key)
- Agenda from the device's calendars
- Optional NYC subway arrivals + service alerts (MTA GTFS-realtime, no key)
- App tiles: YouTube, Netflix, Paramount+, Xbox (Wake-on-LAN), plus Apps / Settings
- Music visualizer that appears when a Sonos speaker on the network is playing
- Voice-triggered picture cards (`am start ... --es show dog`)

No Gradle: `build.ps1` runs aapt2 → javac → d8 → zipalign → apksigner directly.

## Build

Needs JDK 17 and the Android SDK (build-tools + a platform) in `../tools/jdk` and `../tools/sdk`, or edit the paths at the top of `build.ps1`.

```powershell
.\build.ps1
```

A debug keystore is generated on first build (not committed).

## Install

Enable network debugging on the TV, then:

```powershell
Copy-Item device.example.ps1 device.local.ps1   # fill in your TV IP and settings
.\deploy.ps1
```

To make it the home screen, disable the stock launcher, e.g. `adb shell pm disable-user com.google.android.tvlauncher` (undo with `pm enable`).

## Settings

All personal settings live on the device (SharedPreferences), set via intent extras:

| key           | example                                 |
|---------------|-----------------------------------------|
| `subwayStops` | `A27\|42 St\|A,C,E;R20\|14 St\|N,Q,R`   |
| `sonos`       | `192.168.1.20,192.168.1.21`             |
| `calendars`   | `Family,Work`                           |
| `xboxLiveId`  | `FD00XXXXXXXXXXXX`                      |
| `xboxIp`      | `192.168.1.30`                          |

```powershell
adb shell am start -n com.home.frame/.MainActivity --es subwayStops "A27|42 St|A,C,E"
```

## License

Code: MIT (see `LICENSE`). Fonts: Montserrat (SIL OFL 1.1) and GNU FreeSans (GPLv3 with font exception), licenses in `licenses/`. App names and logos belong to their owners.
