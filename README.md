# Wafflr

An offline "read this to me" app for Android, built as a reading aid for people with dyslexia.
A floating button sits over any app; select text or draw around it and Wafflr reads it aloud with
natural Kokoro or Piper voices, entirely on the phone. A Windows version is planned later.

Plan: see the project's development plan doc. Status: **Phase 0 (feasibility spike)**.

## Phase 0: what this build tests

1. **Voice speed.** How fast each voice starts talking on a Pixel 8 Pro, and whether it keeps up
   with real time. Piper "Alan" is built in; Piper "Jenny" and the Kokoro voices download once.
2. **Text selection.** Which apps tell an accessibility service what text you selected (needed for
   a Wispr-style popup), and whether "Read aloud" appears in the text-selection menu.

## Installing a test build

Every push builds an APK on GitHub Actions.

- Newest build from any branch: the **Latest test build** pre-release on the
  [Releases page](../../releases/tag/test-build). Open it on your phone and tap the `.apk`.
- Builds from `main` are also on the project's GitHub Pages site.

The first install asks you to allow installs from your browser. Later builds install over the top.

### Turning on the selection probe

Android blocks accessibility switches for apps installed outside the Play Store until you allow it:

1. Settings → Accessibility → Wafflr selection probe. If it is greyed out, continue:
2. Settings → Apps → Wafflr → ⋮ (top right) → **Allow restricted settings**.
3. Go back and switch the probe on.

### Running the tests

In the app, follow sections 1 and 2, then tap **Copy results** and paste the report into the
project chat.

## Building locally

Needs JDK 17 and the Android SDK (API 35).

```sh
./scripts/fetch-deps.sh   # downloads sherpa-onnx (.aar) and the built-in voice
./gradlew assembleDebug
```

`app/debug.keystore` is a throwaway debug signing key, committed so every CI build can install
over the previous one. It must not be used for a Play Store release.

## Credits and licences

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) runs the voices.
- Kokoro-82M (Apache-2.0) and Piper voices; each Piper voice has its own licence, to be checked
  before any public release.
