# Days Left

Personal Android countdown app: days + hours left shown in the status bar (pinned notification), templates, completion states, optional clock-size overlay text.

## Build
1. Install Android Studio (or JDK 17+ and the Android SDK, platform 36).
2. Open this folder in Android Studio, or run: `./gradlew assembleDebug`
3. APK: `app/build/outputs/apk/debug/app-debug.apk`

## Layout
- `Calc.java` – all countdown maths (pure java.time)
- `Store.java` – countdown model + settings storage
- `CountdownService.java` – foreground service: notifications, status-bar icon, overlay text, finish alerts
- `MainActivity.java` – all UI (plain Android views, no libraries)
