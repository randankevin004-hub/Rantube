# Rantube

Rantube is an original Android media app inspired by the broad feature areas observed in the supplied Snaptube APK. Its name, visual design, and source code are new; it does not reuse Snaptube assets or implementation.

## Current implementation

- Home screen with a rounded, clipboard-pasteable media link field.
- yt-dlp powered page inspection that discovers available resolutions and offers best quality, available height limits, and audio-only MP3.
- FFmpeg-assisted merging when a service provides video and audio as separate streams.
- Device media library populated only through Android's document picker.
- In-app audio/video playback with Media3.
- Live download progress, failure details, completed-file open/share actions, and saved output in `Downloads/Rantube`.
- Share completed media files and Rantube links through Android's system share sheet.
- Search over imported media.
- Dark, high-contrast UI with mint accents and a compact bottom navigation.
- Branded startup screen while the bundled download engine initializes.

Rantube uses yt-dlp for media pages and direct file links. It checks for a stable yt-dlp update once a week and continues with the bundled version if that check fails. Whether an extractor succeeds depends on the page being public and supported by yt-dlp; some services may require sign-in, change their pages, or block downloads. Rantube does not bypass DRM or access controls. Only save media you have permission to download.

The yt-dlp Android wrapper and its FFmpeg runtime are GPL-3.0, so Rantube is distributed under GPL-3.0-only. See [LICENSE](LICENSE) and the bundled third-party notices in `app/src/main/assets/`.

## Build the app

Open this folder in Android Studio with Android SDK 37 installed, then run the `app` configuration on an arm64 Android device or an x86_64 emulator. The project pins Android Gradle Plugin 9.4.1, Gradle 9.6.0, Compose BOM 2026.09.00, Media3 1.11.1, and yt-dlp Android 0.18.1. The APK is larger because Python, yt-dlp, and FFmpeg are bundled; first launch also unpacks engine files. The website workflow builds an arm64-only APK for phones; local Android Studio builds include arm64 and x86_64 for devices and emulators.

From a terminal with JDK 17 or newer and Android SDK 37 installed, build a debug APK with:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Download website

The responsive download page is in `site/`. The workflow at `.github/workflows/android-pages.yml` builds the debug APK, puts it at `site/downloads/Rantube.apk`, and publishes both files to GitHub Pages whenever a commit reaches `main`.

After the first push, enable GitHub Pages for the repository using **Settings → Pages → Build and deployment → GitHub Actions** if GitHub has not enabled it automatically. The public download page URL will be `https://<account>.github.io/<repository>/`.

The workflow produces a debug-signed APK for personal installation. Android will ask you to allow installs from the browser used to download it. Keep that in mind if you later switch to a separately signed production release; Android treats different signing keys as different app publishers.
