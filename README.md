# Voice_EXApp

Native Android voice-practice app built with Kotlin, Jetpack Compose, Vosk offline speech recognition, and Android Text-to-Speech.

## Build

Open the project in Android Studio or build a debug APK with JDK 17 and Android SDK Platform 35:

```sh
./gradlew :app:assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`. Download the latest APK from the [latest release](https://github.com/forcontactmail/Voice_EXApp/releases/latest/download/Voice_EXApp-debug.apk), or browse all versions on the [Releases page](https://github.com/forcontactmail/Voice_EXApp/releases).

Allow microphone access when prompted. On first launch, the app downloads the Vosk English (US) speech model (about 41 MB). After it is installed, speech recognition works offline. The model is stored in the app's private data and is not included in the APK. Text-to-speech voices still depend on the voices installed in Android.

During practice, a persistent Android notification indicates that the voice practice is active. Microphone listening continues while the screen is locked; stop the practice in the app to end microphone use and dismiss the notification. English answers and voice commands use the bundled Vosk English (US) model. The current position in the saved phrase list is restored after restarting the app.

### Voice commands

Say these commands in English (the selected English recognition locale is used):

- **Repeat the current phrase:** “Repeat phrase” or “Repeat”.
- **Move to the next phrase:** “Skip” or “Skip question”.
- **Go back one phrase:** “Previous phrase” or “Go back one phrase”.
- **Go back several phrases:** “Go back three phrases”, “Previous two questions”, or use a number, for example “Go back 4 phrases”. The position stops at the first phrase if the requested number goes past the beginning.
- **Return to the beginning:** “First phrase” or “Start from the beginning”.
- **Hear your first-try rating:** “Rating”. The app reports how many unique phrases you have answered and the percentage answered correctly on the first attempt. The rating is saved on this device across app restarts and resets when the phrase list changes or you clear it.
- **Clear your first-try rating:** “Clear rating”. This resets the current statistics so a new rating starts from zero.

Open the hamburger menu and choose **Settings** to import or edit the practice list and configure speech voices. Recognition uses the Vosk English (US) model and its endpoint detection. Russian and English text-to-speech voices are selected separately; all available English voices are listed. Voice selections are preserved across app restarts. If speech is not recognized, the app automatically restarts listening until you stop the practice.

Under **Settings → Answer checking**, choose a delay from 0 to 5 seconds in 0.5-second steps before the app evaluates a recognized translation. The selected delay is saved on the device.
