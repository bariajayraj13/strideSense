# StrideSense Java

StrideSense is an Android Studio Java app for recording a relative indoor walking path. It shows a live camera preview, a top-down route map, step and distance estimates, detected printed landmarks, local session save/load, and CSV/JSON export.

## Tracking used

- **ARCore visual-inertial tracking** is the preferred mode. ARCore combines camera imagery and phone motion sensors to estimate the camera pose within a local coordinate frame. The route is relative to the place and direction where a recording starts.
- **Printed image landmarks** are four reference images registered with ARCore Augmented Images. The app places a gold pin on the map the first time it sees a card. Seeing that same card again during a recording applies a small, smoothed positional correction to the route. Print PRINT-MARKERS-A4.pdf on A4 at Actual size / 100%; each square is 85 mm wide.
- **Sensor PDR fallback** uses the phone step detector (or a basic accelerometer threshold) and rotation-vector heading. Its distance uses a user-calibrated average step length and will drift more than ARCore.

This app recognizes the four included printed image targets. It does not recognize arbitrary household objects, run ORB-SLAM3, produce a surveyed floorplan, or guarantee a particular accuracy. ARCore support varies by device. Low light, blank walls, fast movement, motion blur, changing the phone's orientation, and long routes can reduce tracking quality. Camera frames are processed locally and are not saved by the app.

## Open and build in Android Studio on Windows

1. Extract the ZIP to a normal folder, for example C:\Users\YourName\AndroidStudioProjects\StrideSenseJava.
2. Open Android Studio and choose Open. Select the extracted StrideSenseJava folder (the folder containing settings.gradle and gradlew.bat).
3. Allow Gradle sync to finish. Install Android SDK Platform 35 if Android Studio asks.
4. To make a debug APK, choose Build > Build Bundle(s) / APK(s) > Build APK(s).
5. When Android Studio reports the build succeeded, choose Locate. The APK is at app\build\outputs\apk\debug\app-debug.apk.
6. To run directly, connect an Android phone with USB debugging enabled, select it in the device menu, then press the green Run button.

The project uses Java 17 source compatibility, Android Gradle Plugin 8.13.2, Gradle 8.14.6, Android SDK 35, and ARCore 1.56.0. Gradle may download Android/ARCore dependencies on the first sync, so an internet connection is needed for that first setup.

## Install and try it on a phone

1. Use an ARCore-supported Android phone with a rear camera and Google Play Services for AR installed and updated. Check Google's supported-device list if the app reports ARCore is unavailable.
2. Grant camera permission. The camera preview should say ARCore visual tracking after it finds the room.
3. Print and cut the four marker cards from PRINT-MARKERS-A4.pdf. In the printer dialog select A4 and Actual size / 100%, with Fit to page off. Check that a square measures 8.5 cm across.
4. In a well-lit room, start a route. Walk slowly and keep some room detail in the camera view. Point the camera at each marker until its gold pin and ID appear on the map. Revisit a marker to exercise the map re-alignment feature.
5. Stop and save the route. Use Load, CSV, or JSON to check the saved session. The export picker lets you choose where to write the file.
6. For a simple accuracy check, walk a short rectangle whose side lengths you measured with a tape measure, then compare the route's proportions and endpoint. Repeat in good light with visible markers. Results are device and room dependent; this is a demonstration tracker, not a measurement instrument.

If ARCore is unsupported or cannot start, the app uses a camera-only preview and labels the path as sensor dead-reckoning. Use Calibrate PDR after walking a measured distance and counting your steps to improve that fallback distance estimate. Calibration does not affect ARCore mode.

## Project layout

- app/src/main/java/com/stridesense/app/ — all Android application logic in Java
- app/src/main/assets/markers/ — the four ARCore reference target images
- PRINT-MARKERS-A4.pdf — ready-to-print landmark sheet
- app/build.gradle — Android and ARCore dependencies
- gradlew.bat and gradle/wrapper/ — Gradle wrapper for Windows

## Build from a Windows terminal

From the project folder, run:

    .\gradlew.bat assembleDebug

The generated debug APK is app\build\outputs\apk\debug\app-debug.apk.
