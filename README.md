# Wafflr

An offline "read this to me" app for Android, built as a reading aid for people with dyslexia.
A floating button sits over any app; select text or draw around it and Wafflr reads it aloud with
natural Kokoro or Piper voices, entirely on the phone. A Windows version is planned later.

Plan: see the project's development plan doc. Status: **Phase 2 (read part of the screen)**.

## Using it

- **Floating button** (over every app): tap to read the screen, tap again to pause or resume,
  long-press to stop, drag to move. While reading, ⏮ and ⏭ go back or forward a sentence.
- **Draw around text**: long-press the floating button, then drag a box (or switch to
  freehand and draw a shape) around the text. Wafflr screenshots that area and recognises the
  text on the phone (ML Kit, offline), so it also works on PDFs and images.
- **Copied text**: select text and tap *Copy*, then tap the floating button within two
  minutes; it reads the copied text instead of the whole screen.
- **Share menu**: *Share → Read with Wafflr* works in most apps. Some apps (e.g. Chrome) also
  show *Read with Wafflr* straight in the selection menu.
- **Voices**: Piper Alan is built in; Piper Jenny and six Kokoro voices download once, then work
  offline. Pick the voice and speed in the app.

Phase 0 findings: Piper starts speaking in about 0.3 s on a Nothing Phone 3a, Kokoro in about
3 s. No app reports text selections to accessibility services, so there is no automatic popup on
selection; the floating button is the main way in.

## Installing a test build

Every push builds an APK on GitHub Actions.

- Newest build from any branch: the **Latest test build** pre-release on the
  [Releases page](../../releases/tag/test-build). Open it on your phone and tap the `.apk`.
- Builds from `main` are also on the project's GitHub Pages site.

The first install asks you to allow installs from your browser. Later builds install over the top.

### Turning Wafflr on

Android blocks accessibility switches for apps installed outside the Play Store until you allow it:

1. Settings → Accessibility → Wafflr. If it is greyed out, continue:
2. Settings → Apps → Wafflr → ⋮ (top right) → **Allow restricted settings**.
3. Go back and switch the probe on.

### Reporting results

In the app, tap **Copy report** and paste it into the project chat. It lists timings for each
read and any error or crash.

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
