# Voice_EXApp

Native Android voice-practice app built with Kotlin, Jetpack Compose, Android SpeechRecognizer, and Text-to-Speech.

## Build

Open the project in Android Studio or build a debug APK with JDK 17 and Android SDK Platform 35:

```sh
./gradlew :app:assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`. Download the latest APK from the [latest release](https://github.com/forcontactmail/Voice_EXApp/releases/latest/download/Voice_EXApp-debug.apk), or browse all versions on the [Releases page](https://github.com/forcontactmail/Voice_EXApp/releases).

Allow microphone access when prompted. Speech recognition and voice availability depend on the Android speech services and language packs installed on the device; recognition may require an internet connection.

Speech recognition uses the Android speech service's default silence detection because custom silence thresholds are not handled consistently by all services. Choose an available English recognition language in "Язык распознавания перевода"; the list is provided by the installed Android recognition service and the selection is saved on the device. Russian and English text-to-speech voices are selected separately; all available English voices are listed. Voice and recognition-language selections are preserved across app restarts, including when a previously selected option is temporarily unavailable. If speech is not recognized, the app keeps listening until you stop the practice.
