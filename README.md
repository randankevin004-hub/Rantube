# Rantube

Rantube is an original Android media app inspired by the broad feature areas observed in the supplied Snaptube APK. Its name, visual design, and source code are new; it does not reuse Snaptube assets or implementation.

## Current implementation

- Home screen with an inline, clipboard-pasteable direct media URL field, original-quality downloads, and file import.
- Device media library populated only through Android's document picker.
- In-app audio/video playback with Media3.
- Direct HTTP(S) file downloads through Android DownloadManager, with a persisted queue, progress bars, failure details, and an action to open completed files.
- Share completed media files and Rantube links through Android's system share sheet.
- Search over imported media.
- Dark, high-contrast UI with mint accents and a compact bottom navigation.

Rantube accepts direct file URLs you have permission to save. It does not extract protected streams or bypass a service's download controls. The imported APK's class inventory also suggests browser, bookmarks/history, batch download, vault, and cleanup features; those require separate feature work and are not represented as completed here.

## Build the app

Open this folder in Android Studio with Android SDK 36 installed, then run the `app` configuration on a device or emulator. The project pins Android Gradle Plugin 9.4.1, Gradle 9.6.0, Compose BOM 2026.09.00, and Media3 1.11.1.

From a terminal with JDK 17 or newer and Android SDK 37 installed, build a debug APK with:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Download website

The responsive download page is in `site/`. The workflow at `.github/workflows/android-pages.yml` builds the debug APK, puts it at `site/downloads/Rantube.apk`, and publishes both files to GitHub Pages whenever a commit reaches `main`.

After the first push, enable GitHub Pages for the repository using **Settings → Pages → Build and deployment → GitHub Actions** if GitHub has not enabled it automatically. The public download page URL will be `https://<account>.github.io/<repository>/`.

The workflow produces a debug-signed APK for personal installation. Android will ask you to allow installs from the browser used to download it. Keep that in mind if you later switch to a separately signed production release; Android treats different signing keys as different app publishers.

