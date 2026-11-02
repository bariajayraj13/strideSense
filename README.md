# StrideSense Android

StrideSense is a native Android Studio starter project for recording an indoor, top-down movement trace. It includes a live camera preview, a real-time 2D trajectory, start/stop/reset/save/load controls, and CSV or JSON export.

## Tracking implementation

- **ARCore visual-inertial pose:** On supported devices, ARCore supplies the camera pose used to draw a relative X/Z path. This is local visual-inertial tracking. It is not ORB-SLAM3, does not create a globally referenced map, and can drift or pause when the camera sees too few stable visual features.
- **Sensor PDR fallback:** If ARCore is unsupported or cannot be started, the app uses the phone's step detector (or a basic accelerometer step estimate) and rotation-vector heading. The starter stride length is fixed at **0.72 m per step**. This estimate is not calibrated to the user and heading can be affected by sensor bias and magnetic interference.
- **Relative 2D graph:** The graph shows the path relative to the session start. It is useful for experimentation and visualization, not navigation, surveying, or safety decisions.

The app uses ARCore as an optional capability. The camera preview remains available through Android Camera2 when ARCore cannot run. Sessions are stored in the app's private files directory. Camera frames are not written to disk or sent to a server. CSV/JSON export uses Android's document picker, so the user chooses where to save the exported file.

## Requirements

- Android Studio with Android SDK Platform 35 (or a newer compatible SDK setup).
- JDK 17 for the Android Gradle Plugin.
- A phone or emulator running Android 7.0 / API 24 or newer, with a camera. ARCore visual tracking additionally needs a supported device and Google Play Services for AR. Devices without ARCore can still use the sensor fallback when the required sensors are present.
- Internet access on the first Gradle sync/build to download the Gradle distribution and project dependencies.

The project is configured with Android Gradle Plugin 8.13.2, Gradle 8.14.6, Kotlin 2.2.20, compile/target SDK 35, and ARCore SDK 1.56.0.

## Open and build a debug APK on Windows

1. Extract `StrideSense-Android-Studio-Project.zip` to a folder.
2. In Android Studio, choose **Open** and select the extracted `StrideSenseAndroid` folder (the folder containing `settings.gradle.kts`).
3. Allow Gradle sync and install any SDK components Android Studio requests. In **Settings → Build, Execution, Deployment → Build Tools → Gradle**, use the Android Studio bundled JDK 17 or another JDK 17 installation.
4. To build from Android Studio, choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Or open PowerShell in the project folder and run:

   ```powershell
   .\gradlew.bat assembleDebug
   ```

6. The debug APK is written to:

   ```text
   app\build\outputs\apk\debug\app-debug.apk
   ```

Install that APK on a test device, grant camera access, and follow the ARCore install/update prompt if one appears. On first launch, ARCore can take time to check device compatibility and download its device profile.

## Using the app

1. Grant camera access to show the preview.
2. Tap **Start**. Move the phone slowly; ARCore tracks recognizable visual detail when supported. Otherwise, carry the device naturally so the step sensor can detect steps.
3. Tap **Stop** to end the recording. **Reset** clears the current on-screen session. **Save** stores the session locally, while **Load** reopens a saved session.
4. Choose **Export CSV** or **Export JSON** to write a copy through the Android document picker.

In ARCore mode the step count is supplied separately by the phone's step sensor when available; the plotted route comes from ARCore pose. In PDR mode the plotted route comes from estimated step length and heading.

## Compatibility and known limits

- ARCore support is device-specific. The app checks availability and treats ARCore as optional; its manifest does not filter out phones that lack ARCore.
- Camera access is required for the preview and for ARCore. If permission is denied, the tracking session cannot run.
- PDR accuracy depends on the device sensors, how the phone is held, stride length, and magnetic conditions. The default stride estimate is intentionally visible in this documentation rather than presented as measured distance.
- ARCore pose is relative and may drift. Pauses in tracking can occur in dark, blank, reflective, or fast-moving scenes.
- No ORB-SLAM3 implementation, camera-image recording, cloud upload, or global positioning is included.

## Project structure

```text
StrideSenseAndroid/
├── app/
│   └── src/main/
│       ├── java/com/stridesense/app/
│       │   ├── MainActivity.kt
│       │   ├── ArCameraPreview.kt
│       │   ├── FallbackCameraPreview.kt
│       │   ├── PdrTracker.kt
│       │   ├── SessionData.kt
│       │   └── TrajectoryView.kt
│       ├── res/
│       └── AndroidManifest.xml
├── gradle/wrapper/
├── build.gradle.kts
└── settings.gradle.kts
```
