# Voice_EXApp

Native Android voice-practice app built with Kotlin, Jetpack Compose, Android SpeechRecognizer, and Text-to-Speech.

## Build

Open the project in Android Studio or build a debug APK with JDK 17 and Android SDK Platform 35:

```sh
./gradlew :app:assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`. Download the latest APK from the [latest release](https://github.com/forcontactmail/Voice_EXApp/releases/latest/download/Voice_EXApp-debug.apk), or browse all versions on the [Releases page](https://github.com/forcontactmail/Voice_EXApp/releases).

Allow microphone access when prompted. Speech recognition and voice availability depend on the Android speech services and language packs installed on the device; recognition may require an internet connection.

The default wait after the end of a spoken translation is 2 seconds. Change it in the "Ожидание перевода, с" setting (1–5 seconds) if the speech service stops listening too soon. The selected value is saved on the device.
