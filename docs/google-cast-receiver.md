# Google Cast Web Receiver

PlayBridge hosts a Custom Web Receiver built on Google's Cast Application
Framework (CAF). Launching the receiver before loading media gives senders a
real application-ready state and shows branded idle artwork on the TV.

The receiver URL is:

```text
https://cast.playbridge.app/cast/
```

The canonical source is under `browser-receiver-rust/web/`. Its reproducible
build writes fingerprinted receiver assets to `web/site/static/cast/`; Google
hosts the CAF runtime loaded by that page.

## One-time Google Cast setup

1. Deploy `web/site` and verify that the receiver URL returns `200` over public
   HTTPS.
2. In the Google Cast SDK Developer Console, add a **Custom Receiver**
   named **PlayBridge**.
3. Set its Receiver Application URL to the URL above.
4. Register the Cast device serial numbers used for pre-publication testing.
5. Copy the generated application ID into the PlayBridge build/release
   configuration.
6. Add the Android package, iOS bundle/application details, and website sender
   details in the console.
7. Test launch, idle, load, stop-to-idle, reconnect, and device-disconnect
   behavior on registered devices.
8. Publish the receiver application before releasing senders that use its
   application ID.

Google's Default Media Receiver application ID, `CC1AD845`, remains the
production-development fallback when no PlayBridge ID is supplied. It cannot
display the PlayBridge receiver UI.
Never ship an unpublished PlayBridge application ID to general users: only
registered test devices can launch an unpublished receiver.

The unpublished PlayBridge test application ID is `30FDC6BC`. Test Desktop
without changing its production default:

```bash
cd desktop
flutter run -d macos --dart-define=PLAYBRIDGE_GOOGLE_CAST_APP_ID=30FDC6BC
```

## Readiness contract

A sender is not connected merely because it opened TLS to CastV2 port `8009`.
It becomes ready only after all of the following:

1. the Cast device reports a receiver application whose `appId` matches the
   configured PlayBridge application ID;
2. that application reports a `sessionId` and `transportId`;
3. the sender connects to the application transport channel; and
4. the media channel answers `GET_STATUS`.

The TV may remain on the branded **Ready to cast** splash with no media loaded.
`LOAD` is a later operation. A media `STOP` returns to that ready state; ending
the receiver application is an explicit, separate operation.

## Release configuration

The unpublished application ID is documented only for registered-device
testing. It must not become a sender or release default until the receiver is
published. All clients continue to fall back to `CC1AD845`.

Configuration points:

- CLI: `PLAYBRIDGE_GOOGLE_CAST_APP_ID` environment variable, or
  `google-cast launch --app-id`.
- Desktop: `--dart-define=PLAYBRIDGE_GOOGLE_CAST_APP_ID=<id>` at Flutter build
  time.
- Android: `PLAYBRIDGE_GOOGLE_CAST_APP_ID` Gradle property or environment
  variable.
- Apple phone: `PlayBridgeGoogleCastApplicationID` target build setting /
  generated Info.plist key.

Do not invent or copy another product's application ID.
