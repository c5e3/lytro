# Refocus — A WiFi Lytro Viewer (Android)

Android app for the first-generation Lytro camera. It talks to the camera over its own WiFi hotspot, decodes the light field on the phone and lets you refocus, change the depth of field and shift the perspective on the GPU.

Android 14 or newer is required.

## Build and install

Open this folder in Android Studio, or build from the command line. Gradle has to run on Android Studio's bundled JDK:

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew assembleRelease
adb install -r app\build\outputs\apk\release\app-release.apk
```

The release build is minified and signed with the debug key, so it installs directly. It is also much faster than the debug build: processing a picture takes about 5 s instead of 40 s.

The debug build installs as a separate app (`app.refocus.debug`), so both can sit side by side.

## Connecting

- **Auto-connect:** if the phone's WiFi gateway is the camera (10.100.1.1) when the app starts, it connects automatically.
- **Manual connect:** if you join the camera's WiFi after opening the app, tap **Connect**.
- **Internet:** camera traffic is bound to the camera's WiFi, so mobile data keeps working at the same time.
- **Camera address:** you can change it under **Settings**.

## Downloading and processing

- **Opening a picture:** downloads its RAW (16 MB) before anything else. If **Download all** is running, the opened picture jumps the queue, and the interrupted file resumes from where it stopped.
- **Download all:** downloads every picture that isn't on the phone yet. It keeps going in the background, with a notification and a Stop action.
- **Processing:** every downloaded picture is decoded and its depth map estimated right away. The picture open in the viewer always goes first; other processing is paused and resumed afterwards.
- **Storage:** downloaded and processed data stays in the app's private storage and never appears in the phone's gallery. Only exports go to shared storage.

## Viewer

- **Focus mode:** tap to focus, pinch to zoom, two fingers to pan, double-tap to zoom in or out.
- **Perspective mode:** drag the picture, or switch on tilt, to move the viewpoint. The depth of field is limited to f/5.3 or narrower in this mode, so the viewpoint has room to move.
- **View menu** (layers icon): photo, depth map or depth overlay.
- **Export** (share icon):
  - *Image:* JPEG or PNG at 1–4×, with an optional 16-bit depth PNG. Saved to Pictures/Refocus.
  - *Animation:* focus sweep, perspective orbit or tap-to-tap focus, as MP4 (Movies/Refocus) or GIF (Pictures/Refocus).
  - *Original:* the RAW and its metadata, saved to Download/Refocus.

## Code

| Module | Contents |
|---|---|
| `core/` | Pure Kotlin, testable on the PC. The camera protocol (`LytroClient`, port of `lytro/camera.py`), the light-field decoder, depth estimation and CPU renderer (`LightField`, port of `lytro/lightfield.py`), GIF and 16-bit PNG writers. |
| `app/camera/` | WiFi detection and binding (`CameraNetwork`), the single camera connection and its priority download queue (`CameraSession`). |
| `app/processing/` | Background decoding and depth estimation (`Processor`). |
| `app/ui/viewer/gl/` | OpenGL ES 3 shift-and-add refocus renderer. |
| `app/service/` | Foreground service for background work. |

`gradlew :core:test` runs the core tests:
- the protocol client against a fake camera (and checks that only known-safe commands are sent);
- the parser against a picture list captured from the real camera;
- the decoder against the Python decoder's output in `../cache`: the views match (correlation above 0.99999) and the depth map correlates at about 0.95.

## Safety

Only the known-safe commands are ever sent: `C2 00 00/01/02`, `C6`, `C4`. The app keeps one connection, sends one command at a time, and backs off for 2, 5 and 10 s before retrying after errors.
