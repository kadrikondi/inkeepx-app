# Master Prompt: Convert Any Web App into an Android WebView APK

**How to use this file:** Fill in the CONFIG block below with your website's details, then paste this ENTIRE file as a prompt to an AI coding agent (Claude Code, etc.). The agent will build the complete Android project and tell you how to get the APK. Following all phases takes minutes, not days.

---

## CONFIG — fill this in before sending

```
APP_NAME        = TendiServe                (the app's display name)
START_URL       = https://tendiserve.com/login   (first page the app opens)
SITE_DOMAIN     = tendiserve.com            (links on this domain stay in the app;
                                             everything else opens in the browser)
PACKAGE_ID      = com.tendiserve.app        (unique Android app id, lowercase)
BRAND_COLOR     = #E8000D                   (main accent color, hex)
BG_COLOR        = #000000                   (splash/offline background, hex)
TAGLINE         = Modern Inventory & POS Solutions   (short line under the welcome text)
GITHUB_REPO     = tendiserve-app            (repo name you will push to)
```

---

## PROMPT TO THE AI AGENT — everything below is the instruction

You are building a production-quality **Android WebView wrapper app** for the website in CONFIG. It must feel like a native app: login session persists, uploads/downloads/camera/printing work, it degrades gracefully on slow or no internet, and it never leaves the user on a blank screen with nothing to tap. Work through the phases **in order** and verify each milestone before moving on. Use plain Java (no Kotlin needed), minSdk 21, targetSdk 34, Gradle 8.4, JDK 17.

---

### PHASE 1 — Project scaffold

Create a standard single-activity Android project:

- `settings.gradle`, root `build.gradle` (AGP 8.x), `gradle.properties` (`android.useAndroidX=true`), `gradle/wrapper/gradle-wrapper.properties` pointing at Gradle 8.4.
- **Pitfall:** `gradle/wrapper/gradle-wrapper.jar` must be the real binary jar. If you cannot fetch it, note it clearly — the GitHub Actions workflow in Phase 8 installs Gradle directly and does not need the wrapper, but local builds do.
- `app/build.gradle`: applicationId = PACKAGE_ID, minSdk 21, targetSdk 34, compileSdk 34, Java 8 compatibility. Dependencies: `androidx.swiperefreshlayout:swiperefreshlayout:1.1.0`, `androidx.core:core:1.12.0`.
- `AndroidManifest.xml`: permissions INTERNET, ACCESS_NETWORK_STATE, CAMERA, WRITE_EXTERNAL_STORAGE (maxSdkVersion 28), READ_EXTERNAL_STORAGE (maxSdkVersion 32). Single launcher activity with `android:configChanges="orientation|screenSize|keyboardHidden"` and **`android:windowSoftInputMode="adjustResize"`**. A `FileProvider` (`androidx.core.content.FileProvider`, authority `${applicationId}.fileprovider`) with a paths XML covering `external-files-path` and `cache-path`.
- Theme: `Theme.Material.Light.NoActionBar` with statusBarColor/navigationBarColor/windowBackground = BG_COLOR. **Pitfall: do NOT use `windowFullscreen` — it hides the clock/battery AND breaks `adjustResize`, so the keyboard covers input fields.**
- Launcher icons in all mipmap densities (48/72/96/144/192 px). Generate simple placeholder icons: BRAND_COLOR rounded square with the first letter of APP_NAME; tell the user how to replace them.

**Milestone 1:** project structure complete; `gradle assembleDebug` has no missing-file errors.

### PHASE 2 — Core WebView with persistent login

Single layout: `SwipeRefreshLayout` → `RelativeLayout` containing (in z-order, bottom to top): WebView → status banner TextView (top-aligned, hidden) → centered ProgressBar spinner → offline/error LinearLayout (hidden) → splash LinearLayout (visible).

WebView settings: JavaScript, DOM storage, database enabled; `setAllowFileAccess(true)`; wide viewport + overview mode; zoom controls off; `setMediaPlaybackRequiresUserGesture(false)`; `MIXED_CONTENT_ALWAYS_ALLOW`; cache mode `LOAD_DEFAULT`.

- CookieManager: accept cookies + third-party cookies, `flush()` in `onPause` and `onPageFinished` → login survives app restarts.
- Session memory in SharedPreferences: store `logged_in` (true when the current URL is not a login page) and `last_url`. On launch: if previously logged in, load `last_url`, else START_URL.
- `shouldOverrideUrlLoading`: URLs containing SITE_DOMAIN stay in the WebView; anything else opens via `ACTION_VIEW` (external browser), wrapped in try/catch for `ActivityNotFoundException`.
- Back button: if `webView.canGoBack()` go back; **otherwise `moveTaskToBack(true)`** — never finish the activity, so reopening the app is instant with no reload.
- Pull-to-refresh via SwipeRefreshLayout, enabled only when page scrollY == 0 so it doesn't fight in-page scrolling. Spinner color = BRAND_COLOR.

**Milestone 2:** app opens the site, login persists after killing and reopening the app, external links open in the browser, back navigation works.

### PHASE 3 — Splash screen with time-based greeting

Full-screen splash (BG_COLOR) covering everything on startup, containing: a logo mark (BRAND_COLOR rounded square + APP_NAME), a greeting TextView set at runtime from the device clock — **"Good Morning" (5–11), "Good Afternoon" (12–16), "Good Evening" (17–20), "Good Night" (otherwise)** — then "Welcome to APP_NAME" in BRAND_COLOR, then TAGLINE in grey, then a small progress spinner.

- Fade the splash out (400 ms alpha animation → GONE) on the **first** `onPageFinished`, guarded by a boolean so it runs once.
- If the offline/error screen must show instead, hide the splash instantly so it never covers those screens.

**Milestone 3:** cold start shows greeting splash, fades smoothly into the loaded page.

### PHASE 4 — Slow-network speed pack

- `prepareForLoad()` helper called before **every** load (initial, refresh, retry, reload): if offline or connection is slow → `setCacheMode(LOAD_CACHE_ELSE_NETWORK)` (instant render of previously visited pages), else `LOAD_DEFAULT`.
- Slow detection: API 23+ → `NetworkCapabilities.getLinkDownstreamBandwidthKbps() < 1500`; older → mobile subtype in the 2G set (GPRS/EDGE/CDMA/1xRTT/IDEN).
- Text-first rendering: in `onPageStarted`, if offline/slow → `setBlockNetworkImage(true)`; in `onPageFinished` → set false (WebView then auto-fetches the held images). Content is readable in a fraction of the time on 2G.
- Status banner (slim, top, semi-transparent dark): "Offline — showing last saved page" when loading without a connection; a 10-second Handler timer during online loads that shows "Slow connection — still loading…" if the page still hasn't finished. Clear the timer in `onPageFinished` and error callbacks.
- On launch with no connection, still ATTEMPT the load (cache-first will serve a previously seen page + banner). Only fall back to the offline screen if that fails too.

**Milestone 4:** with airplane mode on, a previously visited page still opens with the offline banner; on throttled network, text appears before images and the slow banner appears after 10 s.

### PHASE 5 — Never a dead screen: offline, errors, session expiry, auto-reconnect

One reusable full-screen view (BG_COLOR, logo, icon, title, subtitle, buttons) with three modes:

1. **Offline** (`onReceivedError` for the main frame): 📡 "You're Offline" + connect hint + **Try Again** button (BRAND_COLOR). Try Again when still offline plays a small blink animation instead of doing nothing.
2. **Server error** (`onReceivedHttpError`, main frame only, status >= 400 except the session codes): ⚠️ "Something Went Wrong", human wording ("That page could not be found." for 404, "The server had a problem loading this page." otherwise) + "(Error N)" + **Try Again** AND **Go Back** buttons. Go Back → `webView.goBack()` if possible else load START_URL. Critical for gesture-navigation tablets where there is no system back button and a blank error page would otherwise trap the user.
3. **Session expired** (status 401/403/419/440 on the main frame): do NOT show an error screen — toast "Session expired — please log in again.", clear the saved logged-in state, and load START_URL directly. **Pitfall: never trigger this when the failing URL is itself the login page, or you create a redirect loop.**

- Hardware/gesture back while the error screen is visible must reveal the WebView again before navigating back (otherwise the page changes behind a stuck error screen).
- **Auto-reload when internet returns:** register a `ConnectivityManager.NetworkCallback` (registered in `onResume`, unregistered in `onPause`); when connectivity becomes available AND the offline screen is showing, automatically hide it and reload — the user does not even need to tap Try Again.
- Only main-frame failures trigger these screens — a failed image or analytics call must never interrupt the user.

**Milestone 5:** airplane mode → offline screen → turning data back on auto-reloads with no tap; a 404 URL shows the error screen with two working buttons; an expired session lands on the login page with a toast.

### PHASE 6 — Uploads, camera, barcode scanner

- `onShowFileChooser` in a WebChromeClient — without this, `<input type="file">` does nothing in a WebView. Build a chooser combining `fileChooserParams.createIntent()` with a camera capture intent via `EXTRA_INITIAL_INTENTS`.
- **Camera capture pitfall (this exact bug shipped once):** `ACTION_IMAGE_CAPTURE` without `EXTRA_OUTPUT` returns no usable URI → the photo silently vanishes. Pre-create a file in `getExternalFilesDir(DIRECTORY_PICTURES)`, wrap it with the FileProvider, pass it as `EXTRA_OUTPUT` with read/write grant flags, AND explicitly `grantUriPermission()` to every activity resolving the camera intent (some camera apps ignore intent flags). In `onActivityResult`, when the returned intent has no data/clipData but the camera URI is set, deliver the camera URI. Always cancel any previous pending callback before storing a new one.
- **getUserMedia camera (in-page barcode scanner):** override `onPermissionRequest` — for `RESOURCE_VIDEO_CAPTURE`: grant immediately if Android CAMERA permission is already held; otherwise request the runtime permission and grant/deny the web request from `onRequestPermissionsResult`. If permanently denied ("don't ask again"), show your own Allow/Deny dialog whose Allow opens the app's settings page. Deny non-camera resources (mic) you don't support.

**Milestone 6:** file upload works from files AND from camera (photo actually arrives in the page), and the site's camera/barcode feature gets a working permission flow even after a previous denial.

### PHASE 7 — Downloads and printing

- `setDownloadListener` routing three cases:
  1. `data:`/`blob:` URLs → JavaScript bridge: fetch/FileReader in page JS converts to base64, delivers to an `@JavascriptInterface` method, native code decodes and saves.
  2. Authenticated exports (URLs/mime/disposition matching `.csv`, `format=csv`, `text/csv`) → fetch inside the page with `credentials:'include'` so session cookies apply, then the same base64 bridge. Plain DownloadManager would fail these (no session).
  3. Everything else → `DownloadManager` with the WebView's cookies and User-Agent copied onto the request, saving to public Downloads with a completion notification.
- Saving: API 29+ → MediaStore Downloads collection; older → app external files + FileProvider. After saving, toast the filename and try `ACTION_VIEW` (fall back to a share chooser).
- Inject a page script (guarded so it runs once per page) that patches `URL.createObjectURL` to remember blob→base64, intercepts clicks on `a[download]`/CSV links, and patches `HTMLAnchorElement.prototype.click` — this catches JS-triggered exports that never hit the download listener.
- Printing: inject `window.print = function(){ AndroidPrint.print(); }` in `onPageFinished`; the bridge calls `PrintManager` with `webView.createPrintDocumentAdapter()` (A4). Without this, print buttons do nothing.

**Milestone 7:** a normal file download lands in Downloads with a notification; a CSV export from inside the logged-in app saves and opens; the site's print button opens the Android print dialog.

### PHASE 8 — Extras and final polish

- Shake-to-reload: accelerometer listener → "Reload page?" confirm dialog. Register in `onResume`, unregister in `onPause`.
- `webView.onResume()`/`onPause()` in the matching lifecycle methods; flush cookies in `onPause`.
- Progress: show the spinner from `onProgressChanged` while < 100.
- Double-check the keyboard: with `adjustResize` and no fullscreen flag, focusing a bottom-of-page input must keep it visible above the keyboard.

**Milestone 8:** full manual test pass — login persistence, uploads (file + camera), downloads, print, offline → auto-reconnect, 404/500 screens, session expiry, keyboard behavior, splash greeting at different device times.

### PHASE 9 — Build and deliver the APK

Create `.github/workflows/build.yml`: on push/PR to main + manual dispatch → checkout, JDK 17 (temurin), download and unpack Gradle 8.4 directly to `/opt/gradle` and add it to PATH (this avoids depending on the wrapper jar), `android-actions/setup-android@v3`, `gradle assembleDebug`, upload `app/build/outputs/apk/debug/app-debug.apk` as artifact `APP_NAME-debug-apk` (retention 30 days).

Then give the user BOTH delivery paths, exactly:

**Path A — GitHub (no local tools needed):**
1. Create a repository named GITHUB_REPO on github.com
2. Upload/push all project files
3. Open the Actions tab → latest run → download the APK artifact
4. On the phone: allow "Install unknown apps", open the APK, install

**Path B — local build (Android SDK + JDK 17 installed):**
- `gradlew assembleDebug` (or `gradle assembleDebug` if the wrapper jar is unavailable)
- APK at `app/build/outputs/apk/debug/app-debug.apk`

Also note for the user: the debug APK is fine for internal/company use; publishing to Play Store later requires a signed release build (keystore + `assembleRelease`) — offer to set that up on request.

**Milestone 9 (FINAL):** the user has an installable APK of their web app with every feature above working.

---

### Rules for the agent

- Never leave the user on a blank screen in ANY failure state — every state needs a tappable way out.
- All UI colors come from BRAND_COLOR/BG_COLOR; all text mentions APP_NAME — no leftover names from other projects.
- Java only, no extra libraries beyond the two AndroidX dependencies listed.
- After each phase, state what was built and how the user can verify it; at the end, print the full delivery instructions (Path A and B) again.
