# Google Cast Styled Media Receiver

PlayBridge uses a Google-hosted Styled Media Receiver (SMR). Launching the
receiver before loading media gives senders a real application-ready state and
shows branded idle artwork on the TV.

The hosted skin is:

```text
https://playbridge.app/cast/playbridge-receiver.css
```

The repository owns the CSS and referenced SVG artwork under
`web/site/static/cast/`. Google owns and hosts the receiver runtime.

## One-time Google Cast setup

1. Deploy `web/site` and verify that the skin and both SVG URLs return `200`
   over public HTTPS.
2. In the Google Cast SDK Developer Console, add a **Styled Media Receiver**
   named **PlayBridge**.
3. Set its Skin URL to the URL above.
4. Register the Cast device serial numbers used for pre-publication testing.
5. Copy the generated application ID into the PlayBridge build/release
   configuration.
6. Add the Android package, iOS bundle/application details, and website sender
   details in the console.
7. Test launch, idle, load, stop-to-idle, reconnect, and device-disconnect
   behavior on registered devices.
8. Publish the receiver application before releasing senders that use its
   application ID.

Google's Default Media Receiver application ID, `CC1AD845`, remains useful as
an unbranded development fallback. It cannot display the PlayBridge skin.
Never ship an unpublished PlayBridge application ID to general users: only
registered test devices can launch an unpublished receiver.

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

The receiver application ID is intentionally not committed until Google has
issued and published it. All clients should consume the same value from their
release configuration and fall back to `CC1AD845` for local development.

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
