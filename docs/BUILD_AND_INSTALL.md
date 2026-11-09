# Build and install the app from GitHub

GitHub builds the app on every push (Actions tab > **Android build**). To get an APK
that can sign in, GitHub needs two secrets: your Firebase config and a fixed signing key.
You only do this once.

## 1. Make a signing key (once, on your laptop)

Google sign-in only works when the app's signing key is registered in Firebase, so use the
same key for every build. You need Java installed (Android Studio includes it).

```sh
keytool -genkeypair -keystore money-debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10000

# Its SHA-1 and SHA-256 fingerprints, for step 2:
keytool -list -v -keystore money-debug.keystore -storepass android -alias androiddebugkey

# The file as text, for step 3 (macOS: `base64 -i money-debug.keystore | pbcopy`):
base64 -w0 money-debug.keystore > money-debug.keystore.b64
```

Keep `money-debug.keystore` somewhere safe. Don't commit it.

## 2. Firebase: register the app and download `google-services.json`

1. Open [Firebase console](https://console.firebase.google.com) and choose your project.
2. Gear icon > **Project settings** > **General** > **Your apps**.
3. If there is no Android app yet, click **Add app** > Android, with package name
   **`com.nestwise.app`**.
4. In that app's card, click **Add fingerprint** and paste the **SHA-1** from step 1
   (add the SHA-256 too).
5. Make sure **Authentication > Sign-in method > Google** is enabled. Do this *before*
   downloading the file, so the file includes the sign-in client ID.
6. Click **google-services.json** to download it.

## 3. Firebase: the family list (who may use the app)

Only Google accounts on this list can create or join a household, so nobody else can use
your Firebase project even if they get the app. It lives in Firebase, not in this (public)
code.

1. Firebase console > **Firestore Database** > **Data** > **Start collection**.
2. Collection ID: `config`. Document ID: `access`.
3. Add a field `emails` of type **array**, with one **string** per person, in lower case,
   e.g. `you@gmail.com` and `partner@gmail.com`.
4. Save. Deploy the latest `firestore.rules` too (Firestore > **Rules**: paste the file and
   **Publish**).

To let someone else in later, add their email to the array. The app can't read or change
this list.

## 4. GitHub: add the two secrets

On GitHub, open the repo > **Settings** > **Secrets and variables** > **Actions** >
**New repository secret**:

| Name | Value |
|---|---|
| `GOOGLE_SERVICES_JSON` | The whole contents of `google-services.json` (open it in a text editor, copy everything) |
| `DEBUG_KEYSTORE_BASE64` | The contents of `money-debug.keystore.b64` |

## 5. Build and install

1. Repo > **Actions** > **Android build** > **Run workflow** (or just push a change).
2. When it finishes, open the run and download **money-debug-apk** under **Artifacts**.
   It's a zip with the APK inside.
3. Copy the APK to each phone and open it. Android asks you to allow installs from that app
   (Files or Drive) the first time.

## 6. First launch: App Check debug token

The app uses App Check's debug provider (it isn't on the Play Store). On first launch each
phone logs a debug token: connect the phone to a computer and run
`adb logcat | grep DebugAppCheckProvider`, then add the token in Firebase console >
**App Check** > Apps > the Android app > **Manage debug tokens**. Do this once per phone
(and again if you clear the app's data).

## Building on your own computer instead

Put `google-services.json` in `app/`, copy your keystore to `debug.keystore` in the project
root, and run `./gradlew assembleDebug`. Both files are ignored by git.
