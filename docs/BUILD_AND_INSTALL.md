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

If you already have the fixed key, skip the first command and reuse it: every build must
be signed with the same key.

## 2. Firebase: create the project and register the app

1. Open [Firebase console](https://console.firebase.google.com) > **Create a project** (or
   choose yours). Google Analytics is not needed.
2. **Authentication** > **Get started** > **Sign-in method** > **Google** > **Enable**,
   choose a support email, **Save**. Leave every other provider off. Do this *before*
   downloading the config file, so the file includes the sign-in client ID.
3. Gear icon > **Project settings** > **General** > **Your apps** > **Add app** > Android,
   with package name **`com.moneymanagement`** (it can't be changed later).
4. In that app's card, click **Add fingerprint** and paste the **SHA-1** from step 1, then
   again for the **SHA-256**.
5. **Firestore Database** > **Create database** > Standard edition, production mode, a
   location near you (e.g. `asia-south1`; it can't be changed later).
6. **AI Logic** > **Get started** > **Gemini Developer API**. This lets the app read SMS
   with Gemini without any API key in the APK.
7. **App Check** > **Apps** > register the Android app (any provider screen can be skipped;
   the app uses debug tokens, see step 6 below). AI Logic needs App Check.
8. Back in **Project settings** > **Your apps**, click **google-services.json** to download
   it. It must contain an `oauth_client` entry with `"client_type": 3`.

## 3. Firebase: the family list and the rules

Only Google accounts on this list can create or join a household, so nobody else can use
your Firebase project even if they get the app. It lives in Firebase, not in this (public)
code.

1. Firebase console > **Firestore Database** > **Data** > **Start collection**.
2. Collection ID: `config`. Document ID: `access`.
3. Add a field `emails` of type **array**, with one **string** per person, in lower case,
   e.g. `you@gmail.com` and `partner@gmail.com`.
4. Save. Then Firestore > **Rules**: paste the whole of `firestore.rules` and **Publish**.

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
phone makes a debug token. Open **Settings > Health > App Check token (this phone)** and tap
**Get** / **Copy** (or, with a computer, run `adb logcat | grep DebugAppCheckProvider`), then add the token in Firebase console >
**App Check** > Apps > the Android app > **Manage debug tokens**. Do this once per phone
(and again if you clear the app's data). Until a phone's token is added, Gemini can't be
reached from it and SMS are read by the simpler on-phone reader instead.

The build log's **Debug keystore** step prints the key's SHA-1 and SHA-256; if sign-in
fails with error 10, compare them with the fingerprints registered in Firebase.

## Building on your own computer instead

Put `google-services.json` in `app/`, copy your keystore to `debug.keystore` in the project
root, and run `./gradlew assembleDebug`. Both files are ignored by git.
