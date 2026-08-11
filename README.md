# OldTube

An Android app that is YouTube, wearing 2016.

It's a WebView around `m.youtube.com` with a stylesheet injected over the top.
YouTube does all the actual work — your account, subscriptions, recommendations,
comments, playback, everything — and `assets/classic.css` repaints it on the way
in: red app bar with the You[Tube] wordmark, square thumbnails, flat uppercase
buttons, dense two-line cards, no chips, no Shorts, no pills.

## Build

Needs JDK 17 and the Android SDK (platform 35). Android Studio ships both.

```bash
cd mobile && ./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. To put it on a
phone with USB debugging on:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open `mobile/` in Android Studio and press Run.

If Gradle can't find the SDK, create `mobile/local.properties` with
`sdk.dir=C:/path/to/Android/Sdk` (it's gitignored — machine-specific).

## Changing the look

Everything visual is in `app/src/main/assets/classic.css`. Edit, rebuild,
reinstall — no Kotlin involved.

Two levers do most of the work. YouTube themes itself through `--yt-spec-*`
custom properties, so section 1 recolours thousands of components at once
without naming any of them. Everything after that is per-component and written
broadly, because YouTube's element names drift — when one breaks it should cost
you a detail, not the skin.

The palette block has a documented one-line switch between the 2016 red app bar
and the 2017+ white one.

`assets/inject.js` installs the stylesheet and keeps it installed.
`m.youtube.com` is a single-page app, so tapping a video never fires a page-load
callback — the sheet re-seats itself instead, via a `<head>` observer. Note that
its `__CSS__` placeholder must appear **exactly once** in the file: the
substitution in `MainActivity` replaces every occurrence, so a stray one in a
comment gets swapped for a whole stylesheet too.

## Known limits

**Sign-in.** Google rejects OAuth from any WebView, detected via the `; wv`
token in the user-agent string. `MainActivity` strips that token, which is the
standard workaround and generally works — but it's a workaround, and Google can
tighten it at any time.

**Fidelity caps out around 85%.** The DOM underneath is modern YouTube. CSS can
restyle what's there and hide what shouldn't be, but it can't bring back layouts
that no longer have markup. A pixel-exact 2016 rebuild means abandoning the
WebView and drawing the UI natively — which also means losing the Google account
and the personalised home feed, since no public API exposes them.

**It will drift.** YouTube ships markup changes continuously. Expect to touch
the CSS occasionally.

Personal use. Don't ship it — redistributing a YouTube client breaks YouTube's
terms, and the Play Store will reject it.
