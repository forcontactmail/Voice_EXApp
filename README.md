# Voice_EXApp

Native Android voice-practice app built with Kotlin, Jetpack Compose, Android SpeechRecognizer, and Text-to-Speech.

## Build

Open the project in Android Studio or build a debug APK with JDK 17 and Android SDK Platform 35:

```sh
./gradlew :app:assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`. Download the latest APK from the [latest release](https://github.com/forcontactmail/Voice_EXApp/releases/latest/download/Voice_EXApp-debug.apk), or browse all versions on the [Releases page](https://github.com/forcontactmail/Voice_EXApp/releases).

Allow microphone access when prompted. Speech recognition and voice availability depend on the Android speech services and language packs installed on the device; recognition may require an internet connection.

During practice, a persistent Android notification indicates that the voice practice is active. Microphone listening continues while the screen is locked; stop the practice in the app to end microphone use and dismiss the notification. Voice commands are recognized in the selected English recognition locale. The current position in the saved phrase list is restored after restarting the app.

### Voice commands

Say these commands in English (the selected English recognition locale is used):

- **Repeat the current phrase:** “Repeat phrase” or “Repeat”.
- **Move to the next phrase:** “Skip” or “Skip question”.
- **Go back one phrase:** “Previous phrase” or “Go back one phrase”.
- **Go back several phrases:** “Go back three phrases”, “Previous two questions”, or use a number, for example “Go back 4 phrases”. The position stops at the first phrase if the requested number goes past the beginning.
- **Return to the beginning:** “First phrase” or “Start from the beginning”.
- **Hear your first-try rating:** “Raiting” or “Rating”. The app reports how many unique phrases you have answered and the percentage answered correctly on the first attempt. The rating is kept only while the app is running and resets when the app is restarted or the phrase list changes.
- **Clear your first-try rating:** “Clear raiting” or “Clear rating”. This resets the current statistics so a new rating starts from zero.

Open the hamburger menu and choose **Settings** to import or edit the practice list and configure speech voices. Speech recognition uses the Android speech service's default silence detection because custom silence thresholds are not handled consistently by all services. Choose an English locale under **Settings → English recognition language** (US, UK, Australia, Canada, India, Ireland, New Zealand, or Singapore). The selected locale is saved on the device and remains editable during practice; support depends on the installed Android recognition service and language packs. Russian and English text-to-speech voices are selected separately; all available English voices are listed. Voice and recognition-language selections are preserved across app restarts. If speech is not recognized or the service times out, the app automatically restarts listening until you stop the practice.
