<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    <strong>Nuvio for Windows</strong>: an unofficial desktop build of
    <a href="https://github.com/NuvioMedia/NuvioMobile">NuvioMedia/NuvioMobile</a>.
    <br />
    Same app, same account, same addons as on your phone, on a PC with a mouse, a keyboard and a big screen.
  </p>

  [**Download for Windows**](https://github.com/DynamycSound/NuvioMobile/releases/latest) ·
  [Upstream project](https://github.com/NuvioMedia/NuvioMobile) ·
  [nuvio.tv](https://nuvio.tv)

</div>

> [!NOTE]
> This fork is not made or supported by the Nuvio team. Please report problems with the Windows
> build [here](https://github.com/DynamycSound/NuvioMobile/issues), not upstream.

## Install

1. Download `Nuvio-<version>-windows-x64.msi` from the [latest release](https://github.com/DynamycSound/NuvioMobile/releases/latest).
2. Open it. It installs for your user only, so it needs no administrator rights, and adds Nuvio to the Start menu.
3. The installer is not code-signed, so Windows SmartScreen may warn about it the first time:
   choose **More info → Run anyway**. Each release lists the installer's SHA-256 next to it
   (`.msi.sha256`) if you want to check the file:

   ```powershell
   (Get-FileHash .\Nuvio-0.6.5-beta-windows-x64.msi -Algorithm SHA256).Hash
   ```

Sign in with your Nuvio account and your library, addons, Continue Watching and settings come along.

**Requirements:** Windows 10 or 11, 64-bit. A graphics card with DirectX 11 is used for video;
without one (e.g. over Remote Desktop) Nuvio falls back to software rendering.

### Updates

Nuvio checks this repository's releases when it starts. A new version downloads in the background,
its checksum is verified, and the app asks to restart to install it. You can turn automatic
installs off in **Settings**, next to **Check for updates**.

Releases follow upstream's: a Windows build carries the same version number as the upstream
release it was built from.

## What's in the Windows build

Everything in the shared app (home, search, details, addons, Real-Debrid and other debrid
services, Trakt/Simkl, profiles, downloads, watch parties, settings sync), plus:

- **A real video player.** Playback uses [libmpv](https://mpv.io): hardware decoding (D3D11VA)
  and GPU rendering, so 4K HDR remuxes play smoothly even on integrated graphics. HDR is
  tone-mapped for SDR screens. Audio tracks, embedded and addon subtitles, chapters and
  subtitle styling work as on Android, with subtitles sized for a desktop screen.
- **Fullscreen:** borderless and covering the taskbar. The player opens in fullscreen;
  F11 or F toggles it, Esc leaves it.
- **Mouse:** moving the mouse shows the player controls, and the cursor hides with them. The
  mouse's back button goes back.
- **Keyboard:**

  | Key | Action |
  | --- | --- |
  | Space, K, media Play/Pause | Play / pause |
  | ← / J, → / L | Seek back / forward 10 seconds |
  | F, F11 | Toggle fullscreen |
  | Esc | Leave fullscreen, otherwise go back |

- **Windows integration:** one window only (opening Nuvio again brings the running window to the
  front), notifications for new episodes, and the screen stays awake during playback.

Settings and your sign-in live in `%APPDATA%\Nuvio`, caches and crash logs in `%LOCALAPPDATA%\Nuvio`,
and downloads in `Videos\Nuvio`. Uninstalling from **Settings → Apps** keeps all of them.

### What the Windows build doesn't do

- No Picture-in-Picture, and the ExoPlayer- and iOS-only player options have no effect.
- Video is rendered at up to 1920×1080 and scaled to the screen, which keeps 4K playback smooth
  on laptop GPUs. On a 4K display the picture is as sharp as a very good 1080p stream, not true 4K.

## Build from source

Needs JDK 17+ and Git; the Gradle wrapper fetches everything else, including libmpv.

```bash
git clone https://github.com/DynamycSound/NuvioMobile.git
cd NuvioMobile
./gradlew :composeApp:run                      # run from source
./gradlew :composeApp:packageMsi               # build the installer into composeApp/build/compose/binaries
./gradlew :composeApp:desktopTest              # desktop tests (player tests play a short generated video)
```

Like upstream's builds, it reads service keys from `local.properties` in the repository root:
`NUVIO_SUPABASE_URL` and `NUVIO_SUPABASE_ANON_KEY` for sign-in and sync, `TMDB_API_KEY` for
metadata, and optional client IDs such as `TRAKT_CLIENT_ID` or `SIMKL_CLIENT_ID`. Features whose
keys are missing don't work. `-Pnuvio.dataDir=<folder>` starts `run` with a clean profile.

The desktop code lives in `composeApp/src/desktopMain` and `composeApp/desktop.gradle`; the rest
is upstream's shared Kotlin Multiplatform code, merged from `cmp-rewrite`.

## License and credits

Nuvio is made by the [Nuvio team and contributors](https://github.com/NuvioMedia/NuvioMobile/graphs/contributors)
and licensed under the [GNU GPL v3](../LICENSE); this fork is under the same license. Video playback
uses [mpv](https://mpv.io) via [shinchiro's libmpv builds](https://github.com/shinchiro/mpv-winbuild-cmake).
Nuvio doesn't host or provide any content; you add your own sources.
