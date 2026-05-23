# Google Cast App ID

PhairPlay cannot invent a Google Cast App ID locally. The ID is assigned by
Google after registering a receiver application in the Google Cast SDK Developer
Console.

## Get the ID

1. Open the Google Cast SDK Developer Console:
   `https://cast.google.com/publish`
2. Register or sign in with the account that should own the receiver.
3. Add a receiver application.
4. For development, register the Google TV / Android TV test device in the same
   console entry before publishing.
5. Copy the Application ID shown by the console.

Google's current registration docs:
`https://developers.google.com/cast/docs/registration`

For an Android TV native receiver, also associate the Android TV package name
with the Cast App ID in the console. PhairPlay's Google TV package is:

```text
com.phairplay.googletv
```

Google's Android TV receiver overview:
`https://developers.google.com/cast/docs/android_tv_receiver/`

## Build with the ID

Use either a Gradle property:

```bash
./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>
```

or an environment variable:

```bash
PHAIRPLAY_CAST_APP_ID=<APP_ID> ./gradlew assembleGoogletvDebug
```

If no ID is supplied, the Google TV build still succeeds, but PhairPlay marks
Cast as an error at runtime instead of pretending Cast is ready for testing.

Fire TV builds do not use Google Cast because Fire TV does not include Google
Play Services.
