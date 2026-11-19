# Wafflr

An offline "read this to me" app for Android, built as a reading aid for people with dyslexia.
A floating button sits over any app; select text or draw around it and Wafflr reads it aloud with
natural Piper voices, entirely on the phone. A Windows version is planned later.

Plan: see the project's development plan doc. Status: **Phase 4 (voice settings)**.

## Using it

- **Floating controls** (over every app): a small, faded Wafflr logo sits at the screen edge.
  Tap it to open the controls, drag it to move, long-press it to open the app. The speaker
  rises out of the logo: tap it to read the whole screen, tap again to pause or resume.
  While reading, previous, next and stop slide out beside it.
- **Draw around text**: from the open controls, tap the box or pencil button, then drag a box
  (or draw a shape) around the text. Wafflr screenshots that area and recognises the text on
  the phone (ML Kit, offline), so it also works on PDFs and images.
- **Share menu**: *Share → Read with Wafflr* works in most apps. Some apps (e.g. Chrome) also
  show *Read with Wafflr* straight in the selection menu.
- **Voices**: Alan is built in; nine more British, Scottish and American Piper voices download
  once (about 65 MB each), then work offline. The app lists downloaded voices (remove any but
  the last) and voices to download; the voice picker only offers downloaded ones. Kokoro was
  tried and dropped: it took about 3 s to start on a mid-range phone.

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
- Piper voices; each Piper voice has its own licence, to be checked before any public release.
- [Sonic](https://github.com/waywardgeek/sonic) by Bill Cox (Apache-2.0), vendored as
  `app/src/main/java/sonic/Sonic.java`, shifts the pitch.
