# GlamNSmile

Single-module Android project for live face analysis with CameraX and ML Kit.

## What it includes

- Full Android app module in `app/`
- Live camera preview with front/back camera switching
- On-device face detection and lightweight facial metrics
- Heuristic wrinkle visibility, age band, and skin tone estimation from live frames
- Permission flow and a simple analysis dashboard

## Estimation notes

- Wrinkle, age, and skin tone values are heuristic estimates built from face-region sampling.
- They are sensitive to lighting, makeup, blur, filters, and camera quality.
- Treat the output as directional UI feedback, not as a medical or identity-grade result.

## Open and run

1. Open the project in Android Studio with JDK 17.
2. Let Gradle sync and install Android SDK 36 if prompted.
3. Run the app on a device or emulator with camera support.
4. Grant the camera permission when prompted.

## Main files

- `app/src/main/java/com/glamnsmile/faceanalysis/MainActivity.kt`
- `app/src/main/java/com/glamnsmile/faceanalysis/FaceAnalyzer.kt`
- `app/src/main/res/layout/activity_main.xml`
