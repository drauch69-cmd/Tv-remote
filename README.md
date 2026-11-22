# TV Remote

A personal remote control for an onn Google TV box (Android 14). Your iPhone becomes the remote: the TV app serves a remote control web page, and you open that page in Safari.

This is a personal project. The app is sideloaded (installed directly, not from an app store) and is not meant for anyone else.

## What it is

The project has two parts:

1. **The Android TV app** (package `dev.tvremote`). It runs on the onn box and starts a foreground service at boot. That service hosts a small HTTP and WebSocket server on port 8765, built with NanoHTTPD. The TV screen shows the address to open on your phone.
2. **The phone remote.** This is a web page the TV serves at `http://<tv-ip>:8765`. You open it in iPhone Safari, add it to your home screen, and then it runs full-screen like an app.

Phone and TV talk directly over your home Wi-Fi.

**Out of scope:** casting, voice control, a touchpad, automatic TV discovery, and more than one TV.

## Status

| Phase | What it adds | Status |
|---|---|---|
| 1 | Server, pairing, connection status, D-pad, OK, Back, Home, Recents, Menu (opens Search), main phone page | Done |
| 1.5 | Self-update from GitHub Releases | Done |
| 2 | Media controls, volume, mute | Planned |
| 3 | Text input, app launcher, favorites | Planned |
| 4 | Shizuku backend, plus keys that need Shizuku (Menu, Settings, Power, Search, Mute, any key code) | Planned |
| 5 | Macros, and a guide for iOS Shortcuts | Planned |

## Getting the APK

Every push starts a GitHub Actions build named **Build APK**. Builds of the default
branch that are signed with your key (see "Signing key") are also published as a
GitHub Release, so the newest APK is always at this fixed link:

```
https://github.com/drauch69-cmd/Tv-remote/releases/latest/download/tv-remote.apk
```

You only need this link for the first install. After that the app updates itself
(see "Automatic updates").

Every run also keeps the APK as an artifact: **Actions** tab → newest **Build APK**
run → **Artifacts** → `tv-remote-<number>` (a zip; needs you signed in to GitHub).

### Getting the APK onto the TV without a computer

**Option A: Downloader app (easiest)**

1. On the TV, install **Downloader** (by AFTVnews) from the TV's app store.
2. Open Downloader and type the fixed link above.
3. When Android asks, allow Downloader to install unknown apps. The menu wording varies. Look under **Settings → Apps → Security & restrictions** and turn on the install permission for Downloader.
4. Press **Install**.

**Option B: Send Files to TV**

1. Install the **Send Files to TV** app on both the TV and your phone.
2. Send the APK from your phone to the TV over Wi-Fi.
3. On the TV, open the file and install it. Allow the install permission if asked.

**Option C: ADB from a computer**

1. On the TV, turn on network debugging. You unlock the developer menu by tapping the build number seven times in **Settings → System → About**.
2. On your computer, run:

```
adb connect <tv-ip>:5555
adb install -r app-release.apk
```

The `-r` flag replaces the existing app and keeps its data.

## Signing key (one-time setup)

Android only installs an update over an existing app if both are signed with the same key. Every build from GitHub must use the same key, or you will see **"App not installed"** when you update.

The build reads two repository secrets:

- `KEYSTORE_B64`: the base64 text of your key file `release.jks`
- `KEYSTORE_PASSWORD`: the password for that key file

The key alias is `tvremote`. The key password is taken from the same `KEYSTORE_PASSWORD` value, so use one password for both when you create the key.

**Steps:**

1. Create the key file once, on any computer with Java installed:

   ```
   keytool -genkeypair -v -keystore release.jks -alias tvremote -keyalg RSA -keysize 2048 -validity 10000
   ```

   Remember the password you enter. Keep `release.jks` somewhere safe. Do not commit it to the repository; `.gitignore` already excludes `*.jks`.

2. Turn the key file into base64 text:

   - macOS: `base64 -i release.jks -o release.b64`
   - Linux: `base64 -w 0 release.jks > release.b64`

   Open `release.b64` and copy its entire contents.

3. On GitHub, go to the repository **Settings → Secrets and variables → Actions → New repository secret**.
   - Name: `KEYSTORE_B64`. Value: the base64 text from step 2.
   - Name: `KEYSTORE_PASSWORD`. Value: the password from step 1.

4. Re-run the build: **Actions → Build APK → Re-run all jobs**. Check that the run shows no warning.

**If the secrets are missing:** the build still works, but it is signed with a throwaway debug key and the run shows a warning. A debug-signed build will not install over an older build. Uninstall the old app first.

**If the signing key ever changes:** uninstall TV Remote from the TV before installing the new build. This also clears pairing, so every phone must pair again. If you lose `release.jks`, you will need to make a new key and do this once.

## Automatic updates

The TV app keeps itself up to date from the repository's Releases:

- It checks GitHub at start-up and every 6 hours, and downloads any newer build in the background.
- It installs the update when the TV is idle: the screen is off, or nothing is playing and no phone command has come in for 30 minutes.
- **Install now** (on the TV screen, or in the phone's settings sheet) installs right away. A dot on the phone's gear icon means an update is waiting.
- After an update the server restarts on its own and the phone reconnects.

**One-time setup:** in the TV app press **Allow this app to install updates** and turn it on.
The first update shows Android's "Update this app?" screen, so press **Install now** while
you're at the TV and confirm. Android 14 then lets the app install later updates silently,
because it installed itself.

Only builds signed with your key are published, and Android refuses any APK
signed with a different key, so a tampered download can't be installed.

## First run on the TV

1. On the TV, open **TV Remote** from the apps row.
2. The screen shows:
   - the address to open on your phone, such as `http://192.168.1.50:8765`
   - the server status
   - which control methods are active
   - buttons for permissions and for forgetting paired phones
3. **Tip:** give the TV a fixed address. In your router's admin page, look for **DHCP reservation** (also called "address reservation" or "static lease") and reserve an address for the TV. The home-screen bookmark on your phone then keeps working.

## Turning on the Accessibility service

The Accessibility service lets the app press D-pad, OK, Back, Home and Recents, and show the pairing code on screen. Without it, the D-pad and the Back, Home and Recents buttons are greyed out on the phone.

**Easiest on Google TV: use Shizuku.** Google TV usually hides the "Allow restricted
settings" option, so the toggle stays greyed out. If Shizuku is running:

1. In TV Remote, press **Turn on Accessibility with Shizuku**.
2. Allow TV Remote when Shizuku asks.

That's it. From then on, whenever Shizuku is running and the service has been switched
off (for example after an update), TV Remote turns it back on by itself.

Without Shizuku, use the steps below.

1. In the TV Remote app, press **Open Accessibility settings**. Find **TV Remote control** in the list and turn it on. Confirm if asked.
2. If it is greyed out or says **"Restricted setting"**, Android 13 and later is blocking apps installed from outside the store. Do this:
   1. Go back to TV Remote and press **Open App info**.
   2. Open the three-dot menu, or scroll to find it, and choose **Allow restricted settings**. Confirm.
   3. Go back, press **Open Accessibility settings** again, and turn on **TV Remote control**.

   Google TV sometimes does not show this menu. If that happens, use the ADB method in step 3.

3. **ADB fallback.** Run these from a computer with ADB, or from an ADB shell app on the TV with wireless debugging turned on:

   ```
   adb shell appops set dev.tvremote ACCESS_RESTRICTED_SETTINGS allow
   adb shell settings put secure enabled_accessibility_services dev.tvremote/dev.tvremote.services.RemoteAccessibilityService
   adb shell settings put secure accessibility_enabled 1
   ```

   **Warning:** the second command replaces the whole list of enabled accessibility services. If other services are turned on, first check the current list:

   ```
   adb shell settings get secure enabled_accessibility_services
   ```

   Then add TV Remote to that list instead, joining the entries with a colon (`:`).



## Setting up your phone

1. Make sure the iPhone and the TV are on the same Wi-Fi network.
2. Open **Safari** on the iPhone and type the address shown on the TV, such as `http://192.168.1.50:8765`.
3. Tap the **Share** button, then **Add to Home Screen**.
4. Close Safari and open the new home-screen icon. It runs full-screen.
5. **Pair from inside the home-screen app.** Home-screen apps keep their own storage, separate from Safari, so pairing in a Safari tab does not carry over.
6. Tap **Show code on TV**. A 4-digit code appears in the top-right corner of the TV screen. If the Accessibility service is off, the code shows as a message on the TV and on the app's screen instead.
7. Type the code into the phone.

The code is valid for 2 minutes. After 5 wrong tries, pairing is locked for 1 minute.

### Using the remote

- **Press and hold** the D-pad arrows to repeat the press.
- Phones cannot vibrate from a web page, so presses give visual feedback and an optional click sound.
- Tap the **gear icon** on the phone to change settings:
  - Left-hand layout (moves the D-pad to the left side)
  - Click sound on or off
  - **Forget this TV**, which removes pairing on this phone only

### Revoking access

- **One phone:** on that phone, open the gear icon and tap **Forget this TV**.
- **Every phone:** on the TV, press **Forget all paired phones**. Every phone must pair again.

## Notes and limits

- **Volume (phase 2):** the app uses Android's audio manager first. If HDMI-CEC is on, the TV or soundbar may take over volume. The volume buttons may then behave differently or do nothing.
- **Launching apps:** launching apps from the background works because the Accessibility service is active. Android exempts that service from background-start limits.
- **Menu button:** until Shizuku is set up, the Menu button opens Google TV search. There is no Accessibility action for Menu.
- **Text input (phase 3):** works in normal text fields. Some apps, such as YouTube, use their own keyboard and need the Shizuku fallback.

## Troubleshooting

**The page won't load and the iPhone has a VPN on.** Most VPNs send all traffic through
the tunnel, so the phone can't reach the TV. Turn the VPN off, or turn on its
"Allow LAN access" / "Exclude local networks" option.

**The page won't load.**
- Is the phone on the same Wi-Fi as the TV, and not a guest network?
- Has the TV's IP address changed? Check the address on the TV screen and use the new one. A DHCP reservation prevents this.
- Open the TV Remote app and check that the server is running.
- Some routers have **AP isolation** or **client isolation** turned on, which stops devices from reaching each other. Turn it off in the router's Wi-Fi settings.

**"Offline - retrying" after the TV sleeps.** onn boxes may drop Wi-Fi in standby. The page reconnects by itself when the TV wakes up.

**The D-pad is greyed out.** The Accessibility service is off. It can switch off after an app update. Turn it back on, as described above.

**"App not installed" when updating.** The signing key does not match. See [Signing key](#signing-key-one-time-setup). Uninstall the old app, then install the new one. This also clears pairing.

**The pairing code doesn't show on the TV.** The Accessibility service is off. Look at the TV Remote app screen, where the code also appears.

**iPhone asks about Local Network.** iOS does not normally ask for Safari, but if a **Local Network** prompt appears, allow it.

## For developers

### Project layout

```
.github/workflows/build.yml
.gitignore
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/assets/web/app.js
app/src/main/assets/web/icon.svg
app/src/main/assets/web/index.html
app/src/main/assets/web/manifest.json
app/src/main/assets/web/style.css
app/src/main/java/dev/tvremote/App.kt
app/src/main/java/dev/tvremote/BootReceiver.kt
app/src/main/java/dev/tvremote/data/Prefs.kt
app/src/main/java/dev/tvremote/exec/AccessibilityExecutor.kt
app/src/main/java/dev/tvremote/exec/CommandExecutor.kt
app/src/main/java/dev/tvremote/exec/CommandRouter.kt
app/src/main/java/dev/tvremote/pairing/CodeOverlay.kt
app/src/main/java/dev/tvremote/pairing/PairingManager.kt
app/src/main/java/dev/tvremote/server/NetUtil.kt
app/src/main/java/dev/tvremote/server/RemoteService.kt
app/src/main/java/dev/tvremote/server/WebServer.kt
app/src/main/java/dev/tvremote/services/RemoteAccessibilityService.kt
app/src/main/java/dev/tvremote/ui/SettingsActivity.kt
app/src/main/res/drawable/banner.xml
app/src/main/res/drawable/ic_launcher.xml
app/src/main/res/values/strings.xml
app/src/main/res/xml/accessibility_config.xml
build.gradle.kts
docs/protocol.md
gradle.properties
settings.gradle.kts
```

### Code packages

- **`dev.tvremote`** (root): `App.kt` creates the shared objects and sets the port (8765). `BootReceiver.kt` starts the server at boot and after an app update.
- **`server`**: `RemoteService.kt` is the foreground service that keeps the server running. `WebServer.kt` is the HTTP and WebSocket server. It serves the phone page and handles commands. `NetUtil.kt` finds the TV's IP address.
- **`pairing`**: `PairingManager.kt` handles codes, tokens and the wrong-try lockout. `CodeOverlay.kt` shows the pairing code on screen.
- **`exec`**: `CommandExecutor.kt` is the interface that every control method implements. `CommandRouter.kt` sends each command to the first available backend and reports what works. `AccessibilityExecutor.kt` is the Accessibility backend.
- **`services`**: `RemoteAccessibilityService.kt` is the Accessibility service. It does not watch the screen. It performs the D-pad and navigation actions and draws the pairing overlay.
- **`data`**: `Prefs.kt` wraps SharedPreferences for everything the app saves.
- **`ui`**: `SettingsActivity.kt` is the TV screen with the address, status and buttons.
- **Web page**: `app/src/main/assets/web/` holds the phone remote (`index.html`, `app.js`, `style.css`).

The messages between phone and TV are described in [docs/protocol.md](docs/protocol.md).

### Building locally

You need:

- JDK 17
- The Android SDK (command-line tools are enough)
- Gradle 8.14.3

The project uses Android Gradle Plugin 8.10.1 and Kotlin 2.1.21. The app targets Android 13 and later (minimum SDK 33) and is built for SDK 35.

From the project root, run:

```
gradle :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

**Signing locally:** without a `release.jks` file in the project root, the build uses the debug key. To sign with your own key, copy `release.jks` into the project root and set the password first:

```
export KEYSTORE_PASSWORD='your-password'
```

The only runtime dependency is `org.nanohttpd:nanohttpd-websocket:2.3.1`.
