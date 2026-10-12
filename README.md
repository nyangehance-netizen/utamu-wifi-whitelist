# UTAMU Whitelist (Android)

Adds devices one by one to the UTAMU router's Wi-Fi MAC whitelist, straight from your phone. No PC needed.

## Get the APK

**Option A: GitHub builds it (no software to install)**
1. Create an empty private repository on github.com and upload this folder to it (or ask Claude to push it once the repo exists).
2. GitHub builds the app automatically (Actions tab → "Build APK", about 5 minutes).
3. Open the finished run → download **utamu-whitelist-apk** → unzip → `app-debug.apk`.

**Option B: Android Studio**
1. Open this folder in Android Studio.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

Install: copy the APK to the phone, tap it, allow "Install unknown apps" for your file manager when Android asks.

## Use it

1. Connect the phone to the **UTAMU** Wi-Fi.
2. **Add a device:** name + Wi-Fi MAC → Add device. Add **this phone first**.
3. **Router:** check the address (`http://192.168.1.1`), username, type the admin password (not saved).
4. Tap **Add waiting devices to router**. You watch the app log in and add each one. Each device turns *on router* or *failed*.
5. When everyone is listed, turn whitelist mode ON yourself on the router's Access Control page. The app never does this, so it can't lock you out.

Phones use a private (random) MAC per network. On each phone, open the UTAMU Wi-Fi settings and turn off Private / Random MAC, or it can get blocked later. The app warns you when a MAC looks random.

## Link with the whitelist page (optional)

On the **UTAMU Wi-Fi Whitelist** page in claude.ai: Connect your PC → Create PC key → copy. In the app: Settings → paste key → Save key. Devices added on the page then appear in the app, and the page shows their results.

## If a step fails

The log names the step. Open Settings → Router steps, fix that step's selector, Save, then tap Try again on the device.

- Selectors are CSS (`#Frm_MACAddr`, `input[id*='MAC' i]`) or `text=Words` to click visible text.
- Separate alternatives with `||`: `text=Access Control || text=MAC Filter`.
- Only visible elements count, and frames are searched automatically.
- `mac_format`: `colon-upper`, `colon-lower`, `dash-upper`, `dash-lower`, `plain-upper`.
- **Reset** restores the defaults.

To find the right names, open `http://192.168.1.1` in Chrome on a computer, right-click a field → Inspect, and read its `id`.
