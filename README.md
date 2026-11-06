# Voice_EXApp

Native Android voice-practice app built with Kotlin, Jetpack Compose, Android SpeechRecognizer, and Text-to-Speech.

## Build

Open the project in Android Studio or build a debug APK with JDK 17 and Android SDK Platform 35:

```sh
./gradlew :app:assembleDebug
```

The generated APK is at `app/build/outputs/apk/debug/app-debug.apk`. Download the latest APK from the [latest release](https://github.com/forcontactmail/Voice_EXApp/releases/latest/download/Voice_EXApp-debug.apk), or browse all versions on the [Releases page](https://github.com/forcontactmail/Voice_EXApp/releases).

Allow microphone access when prompted. Online speech recognition may require an internet connection. To require local processing, enable **Offline recognition** under **Settings**; this uses Android's on-device recognizer and is available only on Android 13 or later when a speech pack is installed for the selected English locale. The app checks installed on-device languages and does not fall back to online recognition in this mode. Text-to-speech voices may also require downloaded voice packs for offline use.

Choose **Start Phrases** or **Start Words** to practice the matching entries from the SQLite dictionary (`WORD = 0` for phrases and `WORD = 1` for words). During practice, a persistent Android notification indicates that the voice practice is active. Microphone listening continues while the screen is locked; stop the practice in the app to end microphone use and dismiss the notification. Voice commands are recognized in the selected English recognition locale.

Open the hamburger menu and choose **History** to review completed practice sessions. The table shows session date, start/end time, duration, correctly answered questions, first-try and retry counts, and the first-try percentage of correct answers. Tap either answer count to see the questions, their IDs, CEFR levels, and number of hints. Skipped and unanswered questions do not count as correct. Questions and session results are stored in the local SQLite database. Open **Dictionary / CEFR** from the hamburger menu to edit question levels or switch to the word dictionary. The local `Words.txt` asset uses one Russian word, its English translation, and its CEFR level per `|`-separated line; imported words are stored with `WORD = 1`, while phrase questions use `WORD = 0`.

### Voice commands

Say these commands in English (the selected English recognition locale is used):

- **Switch practice list:** “Start Phrases” or “Start Words”.
- **Repeat the current question:** “Repeat question” or “Repeat”.
- **Move to the next question:** “Skip question” or “Skip”.
- **Go back one question:** “Previous question” or “Go back one question”.
- **Go back several questions:** “Go back three questions”, “Previous two questions”, or use a number, for example “Go back 4 questions”. The position stops at the first question if the requested number goes past the beginning.
- **Return to the beginning:** “First question” or “Start from the beginning”.
- **Hear your first-try rating:** “Rating”. The app reports how many unique questions you have answered and the percentage answered correctly on the first attempt. The rating is kept only for the current app session and resets when you restart the app, change the question list, or clear it.
- **Clear your first-try rating:** “Clear rating”. This resets the current statistics so a new rating starts from zero.

Open the hamburger menu and choose **Settings** to import or edit the practice list and configure speech voices. Choose an English locale under **Settings → English recognition language** (US, UK, Australia, Canada, India, Ireland, New Zealand, or Singapore). The selected locale and recognition mode are saved on the device and remain editable during practice; language support depends on the installed Android recognition service and speech packs. Russian and English text-to-speech voices are selected separately; all available English voices are listed. Voice and recognition-language selections are preserved across app restarts. If speech is not recognized or the service times out, the app automatically restarts listening until you stop the practice. If offline recognition does not start, it reports a timeout instead of silently switching to online recognition.

Under **Settings → Answer checking**, choose a delay from 0 to 5 seconds in 0.5-second steps before the app evaluates a recognized translation. The selected delay is saved on the device. To accept equivalent translations, add them as additional `|`-separated fields on the same phrase-list line after the primary English translation.
