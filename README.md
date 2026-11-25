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
| `sonos`       | `192.168.1.x,192.168.1.y`               |
| `calendars`   | `Family,Work`                           |
| `xboxLiveId`  | `FD00XXXXXXXXXXXX`                      |
| `xboxIp`      | `192.168.1.z`                           |

```powershell
adb shell am start -n com.home.frame/.MainActivity --es subwayStops "A27|42 St|A,C,E"
```

## Keeping personal info out

This repo is public, so nothing about your home (addresses, nearby stations, LAN IPs, device IDs, emails) belongs in it. Several layers check for that:

**Where personal settings live.** Only on the TV (SharedPreferences) and in your untracked `device.local.ps1`, which `deploy.ps1` pushes to the device. Code defaults stay empty, and docs use placeholders like `192.168.1.x` and `FD00XXXXXXXXXXXX`.

**Pre-commit hook.** Enable it once per clone:

```powershell
winget install Gitleaks.Gitleaks      # or: brew install gitleaks
git config core.hooksPath .githooks
git config user.email "<id>+<username>@users.noreply.github.com"
```

`.githooks/pre-commit` blocks a commit when:
- gitleaks finds a secret (its default rules) or PII. The custom rules in `.gitleaks.toml` cover private LAN IPs, email addresses other than noreply ones, MAC addresses, Xbox Live IDs, precise lat/lon pairs, and US phone numbers.
- your commit email isn't a GitHub `@users.noreply.github.com` address.
- a local-only file is staged (`device.local.ps1`, `.pii-denylist`, keystores).
- a staged line contains any term from your `.pii-denylist`.

**Personal denylist.** `.pii-denylist` is gitignored and holds one term per line (case-insensitive; `#` starts a comment). Use it for things only you know are personal: your street and nearby stations, your real name and email, your LAN subnet (e.g. `192.168.0.`), your device MAC prefixes.

If you use the [pre-commit](https://pre-commit.com) framework instead, `pre-commit install` runs the same checks via `.pre-commit-config.yaml`.

**CI.** `.github/workflows/pii-scan.yml` runs gitleaks over the full history on every push and pull request. It also fails if any commit author or committer email isn't a noreply address. That catches commits made with `--no-verify` or from a clone without the hook.

**If something leaks anyway.** Assume it's already public: forks, caches and scrapers see a push within minutes.
1. Revoke or rotate anything that works as a credential (tokens, keys, passwords).
2. Remove it from the whole history, not just the latest commit (`git filter-repo --replace-text`, or BFG), then force-push.
3. Ask GitHub Support to purge cached views if it was sensitive.
4. Add the term to `.pii-denylist` (or a rule to `.gitleaks.toml`) so it can't come back.

## License

Code: MIT (see `LICENSE`). Fonts: Montserrat (SIL OFL 1.1) and GNU FreeSans (GPLv3 with font exception), licenses in `licenses/`. App names and logos belong to their owners.
