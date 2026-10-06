package com.inkeepx.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar spinner;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout offlineView;
    private LinearLayout splashView;
    private Button retryButton;
    private Button goBackButton;
    private TextView offlineIcon;
    private TextView offlineTitle;
    private TextView offlineSubtitle;
    private TextView statusBanner;
    private boolean splashDismissed = false;

    // Auto-reload when internet returns while the offline/error screen is showing
    private ConnectivityManager.NetworkCallback networkCallback;
    private SensorManager sensorManager;
    private ShakeDetector shakeDetector;
    private SharedPreferences prefs;

    // Shows "still loading" feedback if a page load drags on over a weak network
    private final Handler slowLoadHandler = new Handler(Looper.getMainLooper());
    private final Runnable slowLoadNotice = () ->
        showBanner("Slow connection — still loading…");
    private static final long SLOW_LOAD_NOTICE_MS = 10_000;
    // Below this measured bandwidth the connection is treated as slow (≈ weak 3G)
    private static final int SLOW_BANDWIDTH_KBPS = 1500;

    // File chooser callback — held so we can deliver the result from onActivityResult
    private ValueCallback<Uri[]> fileChooserCallback;
    // Where the camera writes its photo when the user picks "Camera" in the chooser
    private Uri cameraPhotoUri;
    // WebView camera request — held while we ask for the Android runtime permission
    private PermissionRequest pendingPermissionRequest;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int CAMERA_PERMISSION_REQUEST = 1002;

    private static final String LOGIN_URL      = "https://www.inkeepx.com/login";
    private static final String SITE_DOMAIN    = "inkeepx.com";
    // True once the share/print bootstrap is registered to run at document
    // start; otherwise it is injected (best effort) in onPageStarted.
    private boolean documentStartScriptInstalled = false;
    private static final String PREFS_NAME     = "inkeepx_session";
    private static final String KEY_LAST_URL   = "last_url";
    private static final String KEY_LOGGED_IN  = "logged_in";
    private static final String KEY_CAMERA_ASKED = "camera_asked";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs        = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        spinner      = findViewById(R.id.spinner);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView      = findViewById(R.id.webView);
        offlineView     = findViewById(R.id.offlineView);
        splashView      = findViewById(R.id.splashView);
        retryButton     = findViewById(R.id.retryButton);
        goBackButton    = findViewById(R.id.goBackButton);
        offlineIcon     = findViewById(R.id.offlineIcon);
        offlineTitle    = findViewById(R.id.offlineTitle);
        offlineSubtitle = findViewById(R.id.offlineSubtitle);
        statusBanner    = findViewById(R.id.statusBanner);

        // ── Splash greeting (time of day) ─────────────────────────────────────
        TextView greetingText = findViewById(R.id.greetingText);
        greetingText.setText(getGreeting());

        // ── Cookie persistence ────────────────────────────────────────────────
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // ── WebView settings ──────────────────────────────────────────────────
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);           // needed for file:// URIs from chooser
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // ── JS interfaces ─────────────────────────────────────────────────────
        webView.addJavascriptInterface(new PrintBridge(), "AndroidPrint");
        webView.addJavascriptInterface(new DownloadBridge(), "AndroidDownload");
        webView.addJavascriptInterface(new ShareBridge(), "AndroidShare");

        // navigator.share() does not exist in Android WebView, so the site's
        // invoice "Share" button hides itself. Install a polyfill that routes
        // to the native share sheet — it must exist BEFORE page scripts run.
        installDocumentStartScript();

        // ── Pull-to-refresh guard ─────────────────────────────────────────────
        // (View.setOnScrollChangeListener is API 23+; this works on API 21.)
        webView.getViewTreeObserver().addOnScrollChangedListener(() ->
            swipeRefresh.setEnabled(webView.getScrollY() == 0));
        swipeRefresh.setColorSchemeColors(0xFFE8000D);
        swipeRefresh.setOnRefreshListener(() -> {
            prepareForLoad();
            webView.reload();
        });

        // ── Download listener ─────────────────────────────────────────────────
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("data:") || url.startsWith("blob:")) {
                handleBlobOrDataDownload(url, contentDisposition, mimeType);
            } else if (isLikelyCsvDownload(url, contentDisposition, mimeType)) {
                // Keep CSV export inside WebView session so authenticated downloads work.
                handleAuthenticatedWebDownload(url);
            } else {
                handleUrlDownload(url, userAgent, contentDisposition, mimeType);
            }
        });

        // ── WebViewClient (single, definitive instance) ───────────────────────
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrlOverride(request.getUrl().toString());
            }

            // Android 5–6 (API 21–23) only call this older overload.
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlOverride(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Fallback for WebViews without document-start script support:
                // inject the share/print bootstrap as early as we can.
                if (!documentStartScriptInstalled) {
                    view.evaluateJavascript(buildBootstrapScript(), null);
                }

                // Text-first on weak networks: hold images back so content
                // renders fast; onPageFinished re-enables them and the WebView
                // fetches the held images automatically.
                boolean online = isOnline();
                webView.getSettings().setBlockNetworkImage(!online || isConnectionSlow());

                slowLoadHandler.removeCallbacks(slowLoadNotice);
                if (!online) {
                    showBanner("Offline — showing last saved page");
                } else {
                    hideBanner();
                    slowLoadHandler.postDelayed(slowLoadNotice, SLOW_LOAD_NOTICE_MS);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                spinner.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                slowLoadHandler.removeCallbacks(slowLoadNotice);
                webView.getSettings().setBlockNetworkImage(false);
                dismissSplash();
                if (isOnline()) {
                    hideBanner();
                } else {
                    showBanner("Offline — showing last saved page");
                }
                CookieManager.getInstance().flush();

                // Track login state
                boolean onLoginPage = url != null && url.contains("/login");
                prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, !onLoginPage)
                    .putString(KEY_LAST_URL, onLoginPage ? LOGIN_URL : url)
                    .apply();

                // Make sure the share polyfill + print patch are present even if
                // the early injection was missed (the script is idempotent).
                view.evaluateJavascript(buildBootstrapScript(), null);

                // Patch download flows (including <a download> + blob:) for WebView.
                injectDownloadCompatScript();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Only give up when the page itself failed AND there is no
                // cached copy to fall back on (cache-first already tried it).
                if (request.isForMainFrame()) {
                    slowLoadHandler.removeCallbacks(slowLoadNotice);
                    showOffline();
                }
            }

            // Android 5–6 (API 21–22) only call this older overload, and only
            // for the main frame — without it those devices never saw the
            // offline screen, just a blank WebView error page.
            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return; // new overload handles it
                slowLoadHandler.removeCallbacks(slowLoadNotice);
                showOffline();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                // Expired session leaves a blank dark error page (401/403/419).
                // Instead, clear the saved session state and take the user
                // straight back to the login screen.
                if (!request.isForMainFrame()) return;
                int status = errorResponse.getStatusCode();
                // 403 is NOT treated as expiry: the site returns 403 when a
                // logged-in staff member lacks permission for a page. Bouncing
                // them to /login (which redirects straight back) looked like a
                // broken app. It now gets the error screen with a clear message.
                boolean sessionExpired =
                    status == 401 || status == 419 || status == 440;

                if (sessionExpired) {
                    String failedUrl = request.getUrl().toString();
                    if (failedUrl.contains("/login")) return; // never loop on login itself

                    prefs.edit()
                        .putBoolean(KEY_LOGGED_IN, false)
                        .putString(KEY_LAST_URL, LOGIN_URL)
                        .apply();

                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                            "Session expired — please log in again.", Toast.LENGTH_LONG).show();
                        prepareForLoad();
                        webView.loadUrl(LOGIN_URL);
                    });
                    return;
                }

                // Any other main-page error (404, 500, …) used to leave a blank
                // page with nothing to tap — especially bad on gesture-nav
                // tablets. Show our error screen with Try Again / Go Back.
                if (status >= 400) {
                    slowLoadHandler.removeCallbacks(slowLoadNotice);
                    runOnUiThread(() -> showError(status));
                }
            }
        });

        // ── WebChromeClient — handles file upload chooser + progress ──────────
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                spinner.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                if (newProgress == 100) swipeRefresh.setRefreshing(false);
            }

            // This is the KEY method that makes <input type="file"> work in WebView.
            // Without it, tapping any file/image upload button does absolutely nothing.
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                // Cancel any previous pending callback to avoid leaking it
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                // Build an intent that lets the user pick from files OR camera
                Intent fileIntent = fileChooserParams.createIntent();

                // Camera capture option: without EXTRA_OUTPUT the camera app
                // returns no usable URI and the upload silently fails, so we
                // point it at a file of our own via the FileProvider.
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cameraPhotoUri = null;
                try {
                    File photoDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    if (photoDir == null) photoDir = getCacheDir();
                    File photoFile = new File(photoDir,
                        "camera_" + System.currentTimeMillis() + ".jpg");
                    cameraPhotoUri = FileProvider.getUriForFile(
                        MainActivity.this, getPackageName() + ".fileprovider", photoFile);
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                    cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    // Some camera apps ignore the intent flags — grant explicitly.
                    for (android.content.pm.ResolveInfo ri :
                            getPackageManager().queryIntentActivities(cameraIntent, 0)) {
                        grantUriPermission(ri.activityInfo.packageName, cameraPhotoUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    }
                } catch (Exception e) {
                    cameraPhotoUri = null; // camera option degrades, file picking still works
                }

                // Combine both into a chooser so user can pick source
                Intent chooser = Intent.createChooser(fileIntent, "Select File");
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                    new Intent[]{ cameraIntent });

                try {
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this,
                        "No file manager found", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            // Called when the page (e.g. the barcode scanner) requests camera access
            // via getUserMedia(). Without this the camera stays permanently blocked.
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsCamera = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                            wantsCamera = true;
                            break;
                        }
                    }

                    // We only handle camera here; deny anything else (e.g. mic) we don't support.
                    if (!wantsCamera) {
                        request.deny();
                        return;
                    }

                    if (ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        // Android already allows the camera → grant the web request now.
                        request.grant(new String[]{ PermissionRequest.RESOURCE_VIDEO_CAPTURE });
                        return;
                    }

                    boolean askedBefore = prefs.getBoolean(KEY_CAMERA_ASKED, false);
                    boolean canShowSystemPrompt = ActivityCompat
                        .shouldShowRequestPermissionRationale(MainActivity.this,
                            Manifest.permission.CAMERA);

                    if (askedBefore && !canShowSystemPrompt) {
                        // Permanently denied — Android won't show its dialog anymore.
                        // Offer our own Allow / Deny choice (Allow opens app settings).
                        showCameraPermissionDialog(request);
                    } else {
                        // First time, or the user can still be re-prompted by the system.
                        pendingPermissionRequest = request;
                        prefs.edit().putBoolean(KEY_CAMERA_ASKED, true).apply();
                        ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{ Manifest.permission.CAMERA },
                            CAMERA_PERMISSION_REQUEST);
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingPermissionRequest) {
                    pendingPermissionRequest = null;
                }
            }
        });

        // ── Shake to reload ───────────────────────────────────────────────────
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        shakeDetector = new ShakeDetector(() -> runOnUiThread(() ->
            new AlertDialog.Builder(this)
                .setMessage("Reload page?")
                .setPositiveButton("Yes", (d, w) -> {
                    prepareForLoad();
                    webView.reload();
                })
                .setNegativeButton("No",  (d, w) -> d.dismiss())
                .show()));

        // ── Retry button ──────────────────────────────────────────────────────
        retryButton.setOnClickListener(v -> {
            if (isOnline()) {
                showWeb();
                prepareForLoad();
                webView.reload();
            } else {
                offlineView.animate().alpha(0.5f).setDuration(100)
                    .withEndAction(() ->
                        offlineView.animate().alpha(1f).setDuration(100).start())
                    .start();
            }
        });

        // ── Go Back button (server-error screen) ──────────────────────────────
        goBackButton.setOnClickListener(v -> {
            showWeb();
            prepareForLoad();
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                webView.loadUrl(LOGIN_URL);
            }
        });

        // ── Initial URL ───────────────────────────────────────────────────────
        // Even with no connection we attempt the load: prepareForLoad() switches
        // the WebView to cache-first, so a previously visited page renders from
        // cache. Only if that also fails does onReceivedError show the offline
        // screen.
        boolean wasLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false);
        String  lastUrl     = prefs.getString(KEY_LAST_URL, LOGIN_URL);

        prepareForLoad();
        if (wasLoggedIn && lastUrl != null && !lastUrl.contains("/login")) {
            webView.loadUrl(lastUrl);
        } else {
            webView.loadUrl(LOGIN_URL);
        }
    }

    // ── File chooser result ───────────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback == null) return;

            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK) {
                if (data != null && data.getDataString() != null) {
                    results = new Uri[]{ Uri.parse(data.getDataString()) };
                } else if (data != null && data.getClipData() != null) {
                    // Multiple files selected
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (cameraPhotoUri != null) {
                    // Camera apps return an empty intent when EXTRA_OUTPUT is
                    // used — the photo is in the file we provided.
                    results = new Uri[]{ cameraPhotoUri };
                }
            }
            cameraPhotoUri = null;
            // Deliver result (null = cancelled, which is also correct behaviour)
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    // ── Camera runtime permission result ──────────────────────────────────────
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (pendingPermissionRequest == null) return;

            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (granted) {
                pendingPermissionRequest.grant(
                    new String[]{ PermissionRequest.RESOURCE_VIDEO_CAPTURE });
            } else {
                pendingPermissionRequest.deny();
                Toast.makeText(this,
                    "Camera permission is required to scan barcodes.",
                    Toast.LENGTH_LONG).show();
            }
            pendingPermissionRequest = null;
        }
    }

    // Shown when the camera was permanently denied and the system won't re-prompt.
    // Gives the user an explicit Allow / Deny choice; Allow jumps to app settings.
    private void showCameraPermissionDialog(final PermissionRequest request) {
        new AlertDialog.Builder(this)
            .setTitle("Allow camera access?")
            .setMessage("InkeepX needs the camera to scan barcodes. "
                + "Camera access is currently turned off.\n\n"
                + "Tap Allow to open settings and enable it.")
            .setCancelable(false)
            .setPositiveButton("Allow", (d, w) -> {
                request.deny(); // page request can't wait for the settings round-trip
                openAppSettings();
            })
            .setNegativeButton("Deny", (d, w) -> {
                request.deny();
                d.dismiss();
            })
            .show();
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
            Toast.makeText(this,
                "Enable Camera, then return and tap Scan again.",
                Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                "Open Settings → Apps → InkeepX → Permissions → Camera → Allow.",
                Toast.LENGTH_LONG).show();
        }
    }

    // ── Print bridge ──────────────────────────────────────────────────────────
    private class PrintBridge {
        @android.webkit.JavascriptInterface
        public void print() {
            runOnUiThread(() -> {
                PrintManager printManager =
                    (PrintManager) getSystemService(Context.PRINT_SERVICE);
                PrintDocumentAdapter adapter =
                    webView.createPrintDocumentAdapter("InkeepX Document");
                printManager.print("InkeepX", adapter,
                    new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .build());
            });
        }
    }

    // Decides whether a navigation stays in the app, is a download, or opens
    // in an external app (browser, mail, phone, WhatsApp…).
    private boolean handleUrlOverride(String url) {
        if (url == null) return false;
        if (isLikelyCsvDownload(url, null, null)) {
            handleAuthenticatedWebDownload(url);
            return true;
        }
        if (isSiteUrl(url)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this link.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        return true;
    }

    // Host-based check: "https://evil.com/?r=inkeepx.com" must not count as ours.
    private boolean isSiteUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null) return false;
            if (!scheme.equals("https") && !scheme.equals("http")) return false;
            host = host.toLowerCase();
            return host.equals(SITE_DOMAIN) || host.endsWith("." + SITE_DOMAIN);
        } catch (Exception e) {
            return false;
        }
    }

    // Shared JS: turn a data: URL into {b64, mime} regardless of whether it
    // is base64 or percent-encoded text (the old code assumed base64 and
    // produced a corrupt file for "data:text/csv,...").
    private static final String JS_DATA_URL_HELPER =
        "function __inkeepxParseDataUrl(u) {" +
        "  var c = u.indexOf(','); if (c < 0) return null;" +
        "  var meta = u.substring(5, c); var payload = u.substring(c + 1);" +
        "  var parts = meta.split(';'); var mime = parts[0] || 'application/octet-stream';" +
        "  var isB64 = parts.indexOf('base64') >= 0;" +
        "  var b64;" +
        "  if (isB64) { b64 = payload; }" +
        "  else {" +
        "    try { b64 = btoa(unescape(encodeURIComponent(decodeURIComponent(payload)))); }" +
        "    catch (e) { try { b64 = btoa(unescape(payload)); } catch (e2) { b64 = ''; } }" +
        "  }" +
        "  return { b64: b64, mime: mime };" +
        "}";

    // ── Blob / data: URI download ─────────────────────────────────────────────
    private void handleBlobOrDataDownload(String url, String contentDisposition,
                                          String mimeType) {
        String fileName = null;
        if (contentDisposition != null && contentDisposition.toLowerCase().contains("filename")) {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        }
        String js =
            "(function() {" +
            JS_DATA_URL_HELPER +
            "  var url = " + JSONObject.quote(url) + ";" +
            "  var name = " + JSONObject.quote(fileName == null ? "" : fileName) + ";" +
            "  if (url.indexOf('data:') === 0) {" +
            "    var d = __inkeepxParseDataUrl(url);" +
            "    if (!d) { AndroidDownload.receiveBase64('', 'text/error'); return; }" +
            "    AndroidDownload.receiveFile(d.b64, d.mime, name);" +
            "    return;" +
            "  }" +
            "  fetch(url)" +
            "    .then(function(r) { return r.blob(); })" +
            "    .then(function(blob) {" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function() {" +
            "        var s = String(reader.result || ''); var i = s.indexOf(',');" +
            "        AndroidDownload.receiveFile(i >= 0 ? s.substring(i + 1) : '', blob.type, name);" +
            "      };" +
            "      reader.onerror = function() { AndroidDownload.receiveBase64('', 'text/error'); };" +
            "      reader.readAsDataURL(blob);" +
            "    })" +
            "    .catch(function() { AndroidDownload.receiveBase64('', 'text/error'); });" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private void injectDownloadCompatScript() {
        String js =
            "(function() {" +
            "  if (window.__inkeepxDownloadPatched) return;" +
            "  window.__inkeepxDownloadPatched = true;" +
            "  window.__inkeepxBlobMap = window.__inkeepxBlobMap || {};" +
            "  var map = window.__inkeepxBlobMap;" +
            "  var origCreate = URL.createObjectURL.bind(URL);" +
            "  var origRevoke = URL.revokeObjectURL.bind(URL);" +
            "" +
            "  URL.createObjectURL = function(blob) {" +
            "    var u = origCreate(blob);" +
            "    try {" +
            "      map[u] = {" +
            "        blob: blob," +
            "        b64: ''," +
            "        mime: blob && blob.type ? blob.type : 'text/csv'" +
            "      };" +
            "      var fr = new FileReader();" +
            "      fr.onloadend = function() {" +
            "        var d = String(fr.result || '');" +
            "        var i = d.indexOf(',');" +
            "        if (map[u]) map[u].b64 = i >= 0 ? d.substring(i + 1) : '';" +
            "      };" +
            "      fr.readAsDataURL(blob);" +
            "    } catch (e) {}" +
            "    return u;" +
            "  };" +
            "" +
            "  URL.revokeObjectURL = function(u) {" +
            "    try { delete map[u]; } catch (e) {}" +
            "    return origRevoke(u);" +
            "  };" +
            "" +
            "  function toAbsUrl(href) {" +
            "    try { return new URL(href, location.href).toString(); }" +
            "    catch (e) { return href; }" +
            "  }" +
            "" +
            JS_DATA_URL_HELPER +
            "" +
            "  function sendBlob(blob, name) {" +
            "    var fr = new FileReader();" +
            "    fr.onloadend = function() {" +
            "      var d = String(fr.result || '');" +
            "      var i = d.indexOf(',');" +
            "      var b64 = i >= 0 ? d.substring(i + 1) : '';" +
            "      AndroidDownload.receiveFile(b64, blob.type || 'text/csv', name || '');" +
            "    };" +
            "    fr.onerror = function() { AndroidDownload.receiveBase64('', 'text/error'); };" +
            "    fr.readAsDataURL(blob);" +
            "  }" +
            "" +
            "  function nameFromUrl(u) {" +
            "    try {" +
            "      var p = new URL(u, location.href).pathname.split('/').pop() || '';" +
            "      return /\\.[a-z0-9]{2,5}$/i.test(p) ? decodeURIComponent(p) : '';" +
            "    } catch (e) { return ''; }" +
            "  }" +
            "" +
            "  function handleHref(href, name) {" +
            "    if (!href) return false;" +
            "    var u = toAbsUrl(href);" +
            "    name = name || '';" +
            "    if (u.indexOf('blob:') === 0 && map[u]) {" +
            "      if (map[u].b64) {" +
            "        AndroidDownload.receiveFile(map[u].b64, map[u].mime || 'text/csv', name);" +
            "      } else if (map[u].blob) {" +
            "        sendBlob(map[u].blob, name);" +
            "      } else {" +
            "        return false;" +
            "      }" +
            "      return true;" +
            "    }" +
            "    if (u.indexOf('data:') === 0) {" +
            "      var d = __inkeepxParseDataUrl(u);" +
            "      if (!d) return false;" +
            "      AndroidDownload.receiveFile(d.b64, d.mime || 'text/csv', name);" +
            "      return true;" +
            "    }" +
            "    if (u.indexOf('.csv') >= 0 || u.indexOf('format=csv') >= 0) {" +
            "      var n = name || nameFromUrl(u);" +
            "      fetch(u, { credentials: 'include' })" +
            "        .then(function(r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.blob(); })" +
            "        .then(function(b) { sendBlob(b, n); })" +
            "        .catch(function() { AndroidDownload.receiveBase64('', 'text/error'); });" +
            "      return true;" +
            "    }" +
            "    return false;" +
            "  }" +
            "" +
            "  document.addEventListener('click', function(ev) {" +
            "    var a = ev.target && ev.target.closest ? ev.target.closest('a[download],a[href*=\\\".csv\\\"],a[href*=\\\"format=csv\\\"]') : null;" +
            "    if (!a) return;" +
            "    var href = a.getAttribute('href') || '';" +
            "    if (handleHref(href, a.getAttribute('download') || '')) {" +
            "      ev.preventDefault();" +
            "      ev.stopPropagation();" +
            "    }" +
            "  }, true);" +
            "" +
            "  var origAnchorClick = HTMLAnchorElement.prototype.click;" +
            "  HTMLAnchorElement.prototype.click = function() {" +
            "    try {" +
            "      var href = this.getAttribute('href') || this.href || '';" +
            "      var isDownload = this.hasAttribute('download');" +
            "      if (isDownload || href.indexOf('blob:') === 0 || href.indexOf('data:') === 0 ||" +
            "          href.indexOf('.csv') >= 0 || href.indexOf('format=csv') >= 0) {" +
            "        if (handleHref(href, this.getAttribute('download') || '')) return;" +
            "      }" +
            "    } catch (e) {}" +
            "    return origAnchorClick.apply(this, arguments);" +
            "  };" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    // ── Regular URL download via DownloadManager ──────────────────────────────
    private void handleUrlDownload(String url, String userAgent,
                                   String contentDisposition, String mimeType) {
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            android.app.DownloadManager.Request request =
                new android.app.DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Downloading via InkeepX");
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            request.setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            } else {
                // Public Downloads needs the WRITE_EXTERNAL_STORAGE runtime
                // permission on Android 6–9, which we never request — enqueue()
                // threw a SecurityException and the download silently died.
                request.setDestinationInExternalFilesDir(
                    this, Environment.DIRECTORY_DOWNLOADS, fileName);
            }
            android.app.DownloadManager dm =
                (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException("DownloadManager unavailable");
            dm.enqueue(request);
            Toast.makeText(this, "Downloading " + fileName + "…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // Non-http scheme, DownloadManager disabled, etc. — let another app try.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e2) {
                Toast.makeText(this, "Download failed. Please try again.",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    // ── Authenticated web download (keeps site session/cookies) ──────────────
    private void handleAuthenticatedWebDownload(String url) {
        String js =
            "(function() {" +
            "  var url = " + JSONObject.quote(url) + ";" +
            "  var name = '';" +
            "  fetch(url, { credentials: 'include' })" +
            "    .then(function(r) {" +
            "      if (!r.ok) throw new Error('HTTP ' + r.status);" +
            "      var cd = r.headers.get('Content-Disposition') || '';" +
            "      var m = /filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?/i.exec(cd);" +
            "      if (m) { try { name = decodeURIComponent(m[1]); } catch (e) { name = m[1]; } }" +
            "      return r.blob();" +
            "    })" +
            "    .then(function(blob) {" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function() {" +
            "        var s = String(reader.result || ''); var i = s.indexOf(',');" +
            "        AndroidDownload.receiveFile(i >= 0 ? s.substring(i + 1) : '', blob.type || 'text/csv', name);" +
            "      };" +
            "      reader.onerror = function() { AndroidDownload.receiveBase64('', 'text/error'); };" +
            "      reader.readAsDataURL(blob);" +
            "    })" +
            "    .catch(function() {" +
            "      AndroidDownload.receiveBase64('', 'text/error');" +
            "    });" +
            "})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean isLikelyCsvDownload(String url, String contentDisposition,
                                        String mimeType) {
        String safeUrl = url == null ? "" : url.toLowerCase();
        String safeContentDisposition =
            contentDisposition == null ? "" : contentDisposition.toLowerCase();
        String safeMimeType = mimeType == null ? "" : mimeType.toLowerCase();

        return safeUrl.contains(".csv")
            || safeUrl.contains("format=csv")
            || safeContentDisposition.contains(".csv")
            || safeMimeType.contains("text/csv")
            || safeMimeType.contains("application/csv");
    }

    // ── Download bridge (receives base64 from JS) ─────────────────────────────
    private class DownloadBridge {
        @android.webkit.JavascriptInterface
        public void receiveBase64(String base64, String mimeType) {
            runOnUiThread(() -> saveBase64File(base64, mimeType, null));
        }

        // Same as above but keeps the real file name (e.g. "invoice-1042.pdf")
        // instead of a generic inkeepx_export_<timestamp> name.
        @android.webkit.JavascriptInterface
        public void receiveFile(String base64, String mimeType, String fileName) {
            runOnUiThread(() -> saveBase64File(base64, mimeType, fileName));
        }
    }

    // Strip path separators and anything else a file name must not contain.
    private static String sanitizeFileName(String name) {
        if (name == null) return "";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (s.length() > 120) s = s.substring(0, 120);
        return s;
    }

    private void saveBase64File(String base64, String mimeType, String requestedName) {
        try {
            if (base64 == null || base64.isEmpty()) {
                Toast.makeText(this, "Download failed. Please try again.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (mimeType == null || mimeType.trim().isEmpty()) mimeType = "application/octet-stream";
            // "text/csv; charset=utf-8" → "text/csv"
            mimeType = mimeType.split(";")[0].trim().toLowerCase();
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (ext == null) ext = mimeType.contains("csv") ? "csv"
                : mimeType.contains("pdf") ? "pdf" : "bin";

            String fileName = sanitizeFileName(requestedName);
            if (fileName.isEmpty()) {
                fileName = "inkeepx_export_" + System.currentTimeMillis() + "." + ext;
            } else if (!fileName.contains(".")) {
                fileName = fileName + "." + ext;
            }
            byte[] data = Base64.decode(base64, Base64.DEFAULT);

            Uri fileUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fileUri = saveToPublicDownloads(fileName, mimeType, data);
            } else {
                fileUri = saveToAppExternalFiles(fileName, data);
            }

            openDownloadedFile(fileUri, mimeType);
            Toast.makeText(this, "Saved: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private Uri saveToPublicDownloads(String fileName, String mimeType, byte[] data)
            throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri fileUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (fileUri == null) throw new IllegalStateException("Unable to create Downloads record");

        try (OutputStream os = getContentResolver().openOutputStream(fileUri)) {
            if (os == null) throw new IllegalStateException("Unable to open Downloads stream");
            os.write(data);
            os.flush();
        }
        return fileUri;
    }

    private Uri saveToAppExternalFiles(String fileName, byte[] data) throws Exception {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = getCacheDir();
        File file = new File(dir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }

        return FileProvider.getUriForFile(
            this, getPackageName() + ".fileprovider", file);
    }

    private void openDownloadedFile(Uri fileUri, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(fileUri, mimeType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType);
            share.putExtra(Intent.EXTRA_STREAM, fileUri);
            share.setClipData(ClipData.newRawUri("", fileUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(share, "Open with"));
            } catch (Exception ignored) {}
        }
    }

    // ── Share bridge (navigator.share polyfill → Android share sheet) ────────
    //
    // Android WebView has no Web Share API. The site's invoice / purchase
    // order / delivery note pages show a "Share" button only when
    // navigator.share + navigator.canShare({files}) exist, so on Android
    // the button never appeared. The bootstrap script below defines both;
    // files arrive here as base64, get written to the cache dir and are
    // handed to ACTION_SEND through the FileProvider.
    private class ShareBridge {
        @android.webkit.JavascriptInterface
        public void share(String requestId, String title, String text, String url,
                          String filesJson) {
            runOnUiThread(() -> performShare(requestId, title, text, url, filesJson));
        }
    }

    private void performShare(String requestId, String title, String text, String url,
                              String filesJson) {
        try {
            File shareDir = new File(getCacheDir(), "share");
            if (!shareDir.exists() && !shareDir.mkdirs()) {
                throw new IllegalStateException("Cannot create share folder");
            }
            cleanOldShareFiles(shareDir);

            ArrayList<Uri> uris = new ArrayList<>();
            String mime = null;
            JSONArray files = filesJson == null || filesJson.isEmpty()
                ? new JSONArray() : new JSONArray(filesJson);
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                String b64  = f.optString("b64", "");
                String type = f.optString("type", "").split(";")[0].trim().toLowerCase();
                if (type.isEmpty()) type = "application/octet-stream";
                String name = sanitizeFileName(f.optString("name", ""));
                if (name.isEmpty()) {
                    String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
                    name = "inkeepx_" + System.currentTimeMillis() + "_" + i
                        + "." + (ext == null ? "bin" : ext);
                }
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (data.length == 0) throw new IllegalStateException("Empty file");

                File out = new File(shareDir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(data);
                    fos.flush();
                }
                uris.add(FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", out));
                // One shared type if all files agree, otherwise a wildcard.
                if (mime == null) mime = type;
                else if (!mime.equals(type)) mime = "*/*";
            }

            String body = (text == null ? "" : text);
            if (url != null && !url.isEmpty()) body = body.isEmpty() ? url : body + "\n" + url;

            Intent send;
            if (uris.isEmpty()) {
                if (body.isEmpty() && (title == null || title.isEmpty())) {
                    throw new IllegalArgumentException("Nothing to share");
                }
                send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, body.isEmpty() ? title : body);
            } else if (uris.size() == 1) {
                send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                if (!body.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, body);
                send.setClipData(ClipData.newRawUri("", uris.get(0)));
            } else {
                send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.setType(mime);
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                if (!body.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, body);
                ClipData clip = ClipData.newRawUri("", uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                send.setClipData(clip);
            }
            if (title != null && !title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(send,
                title == null || title.isEmpty() ? "Share" : title);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
            notifyShareResult(requestId, true, null);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app available to share with.", Toast.LENGTH_SHORT).show();
            notifyShareResult(requestId, false, "NotAllowedError");
        } catch (Exception e) {
            Toast.makeText(this, "Could not share: " + e.getMessage(), Toast.LENGTH_LONG).show();
            notifyShareResult(requestId, false, "DataError");
        }
    }

    // Shared files sit in cache; drop anything older than a day.
    private void cleanOldShareFiles(File dir) {
        File[] old = dir.listFiles();
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File f : old) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private void notifyShareResult(String requestId, boolean ok, String errorName) {
        String js = "window.__inkeepxShareResult && window.__inkeepxShareResult("
            + JSONObject.quote(requestId == null ? "" : requestId) + ","
            + (ok ? "true" : "false") + ","
            + JSONObject.quote(errorName == null ? "" : errorName) + ");";
        webView.evaluateJavascript(js, null);
    }

    // Register the bootstrap so it runs before any page script — this is what
    // makes the site's own navigator.share probe succeed. Only our own origin
    // gets the polyfill (and therefore access to the share bridge).
    private void installDocumentStartScript() {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, buildBootstrapScript(),
                    new HashSet<>(Arrays.asList(
                        "https://" + SITE_DOMAIN,
                        "https://*." + SITE_DOMAIN)));
                documentStartScriptInstalled = true;
            }
        } catch (Exception e) {
            documentStartScriptInstalled = false; // fall back to onPageStarted injection
        }
    }

    // Idempotent: defines navigator.share / navigator.canShare backed by the
    // native share sheet, and routes window.print() to Android's PrintManager.
    private String buildBootstrapScript() {
        return
            "(function() {" +
            "  if (window.__inkeepxBootstrapped) return;" +
            "  window.__inkeepxBootstrapped = true;" +
            "" +
            "  if (window.AndroidPrint) {" +
            "    window.print = function() { AndroidPrint.print(); };" +
            "  }" +
            "" +
            "  if (!window.AndroidShare) return;" +
            "  var pending = {};" +
            "  var seq = 0;" +
            "" +
            "  function isBlob(f) { return typeof Blob !== 'undefined' && f instanceof Blob; }" +
            "" +
            "  function readB64(f) {" +
            "    return new Promise(function(resolve, reject) {" +
            "      var fr = new FileReader();" +
            "      fr.onloadend = function() {" +
            "        var s = String(fr.result || ''); var i = s.indexOf(',');" +
            "        resolve(i >= 0 ? s.substring(i + 1) : '');" +
            "      };" +
            "      fr.onerror = function() { reject(fr.error || new Error('read failed')); };" +
            "      fr.readAsDataURL(f);" +
            "    });" +
            "  }" +
            "" +
            "  function makeError(name, msg) {" +
            "    var e = new Error(msg); e.name = name; return e;" +
            "  }" +
            "" +
            "  navigator.canShare = function(data) {" +
            "    if (!data || typeof data !== 'object') return false;" +
            "    if (data.files && data.files.length) {" +
            "      for (var i = 0; i < data.files.length; i++) {" +
            "        if (!isBlob(data.files[i])) return false;" +
            "      }" +
            "      return true;" +
            "    }" +
            "    return !!(data.url || data.text || data.title);" +
            "  };" +
            "" +
            "  navigator.share = function(data) {" +
            "    if (!navigator.canShare(data)) {" +
            "      return Promise.reject(makeError('TypeError', 'Invalid share data'));" +
            "    }" +
            "    var id = String(++seq);" +
            "    var files = data.files ? Array.prototype.slice.call(data.files) : [];" +
            "    var reads = files.map(function(f) {" +
            "      return readB64(f).then(function(b64) {" +
            "        return { name: f.name || '', type: f.type || '', b64: b64 };" +
            "      });" +
            "    });" +
            "    return Promise.all(reads).then(function(list) {" +
            "      return new Promise(function(resolve, reject) {" +
            "        pending[id] = { resolve: resolve, reject: reject };" +
            "        try {" +
            "          AndroidShare.share(id, String(data.title || ''), String(data.text || '')," +
            "            String(data.url || ''), JSON.stringify(list));" +
            "        } catch (e) {" +
            "          delete pending[id];" +
            "          reject(makeError('NotAllowedError', 'Share bridge failed'));" +
            "        }" +
            "      });" +
            "    });" +
            "  };" +
            "" +
            "  window.__inkeepxShareResult = function(id, ok, errName) {" +
            "    var p = pending[id]; if (!p) return;" +
            "    delete pending[id];" +
            "    if (ok) p.resolve();" +
            "    else p.reject(makeError(errName || 'NotAllowedError', 'Share failed'));" +
            "  };" +
            "})();";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isOnline() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo net = cm.getActiveNetworkInfo();
        return net != null && net.isConnected();
    }

    // True when the active connection is measurably weak (≈ 2G / poor 3G).
    private boolean isConnectionSlow() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return false;
            int kbps = caps.getLinkDownstreamBandwidthKbps();
            return kbps > 0 && kbps < SLOW_BANDWIDTH_KBPS;
        }
        NetworkInfo net = cm.getActiveNetworkInfo();
        if (net == null || net.getType() != ConnectivityManager.TYPE_MOBILE) return false;
        switch (net.getSubtype()) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return true;
            default:
                return false;
        }
    }

    // Pick the cache strategy for the next load: cache-first when the network
    // is missing or weak (instant render of previously visited pages), normal
    // HTTP caching otherwise.
    private void prepareForLoad() {
        boolean cacheFirst = !isOnline() || isConnectionSlow();
        webView.getSettings().setCacheMode(cacheFirst
            ? WebSettings.LOAD_CACHE_ELSE_NETWORK
            : WebSettings.LOAD_DEFAULT);
    }

    // Greeting for the splash screen based on the device clock
    private String getGreeting() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 5  && hour < 12) return "Good Morning";
        if (hour >= 12 && hour < 17) return "Good Afternoon";
        if (hour >= 17 && hour < 21) return "Good Evening";
        return "Good Night";
    }

    // Fade the splash out once the first page has rendered (runs only once)
    private void dismissSplash() {
        if (splashDismissed) return;
        splashDismissed = true;
        splashView.animate().alpha(0f).setDuration(400)
            .withEndAction(() -> splashView.setVisibility(View.GONE))
            .start();
    }

    private void showBanner(String message) {
        statusBanner.setText(message);
        statusBanner.setVisibility(View.VISIBLE);
    }

    private void hideBanner() {
        statusBanner.setVisibility(View.GONE);
    }

    private void showOffline() {
        offlineIcon.setText("📡");
        offlineTitle.setText("You're Offline");
        offlineSubtitle.setText("Connect to Wi-Fi or mobile data\nto continue using InkeepX.");
        goBackButton.setVisibility(View.GONE);
        showErrorScreen();
    }

    // Server-side failure (404, 500, …): same screen, different words,
    // plus a Go Back button so the user is never stuck.
    private void showError(int status) {
        offlineIcon.setText(status == 403 ? "🔒" : "⚠️");
        offlineTitle.setText(status == 403 ? "Access Denied" : "Something Went Wrong");
        String hint = status == 404
            ? "That page could not be found."
            : status == 403
            ? "Your account doesn't have permission to view this page."
            : "The server had a problem loading this page.";
        offlineSubtitle.setText(hint + "\n(Error " + status + ")");
        goBackButton.setVisibility(View.VISIBLE);
        showErrorScreen();
    }

    private void showErrorScreen() {
        spinner.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        hideBanner();
        // Splash must not cover the error screen
        splashDismissed = true;
        splashView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        offlineView.setVisibility(View.VISIBLE);
    }

    private void showWeb() {
        offlineView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        sensorManager.registerListener(shakeDetector,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI);
        registerNetworkCallback();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        sensorManager.unregisterListener(shakeDetector);
        unregisterNetworkCallback();
        CookieManager.getInstance().flush();
    }

    // When connectivity comes back while the offline/error screen is up,
    // reload automatically — the user doesn't even need to tap Try Again.
    private void registerNetworkCallback() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Short delay: the network is announced slightly before
                // DNS/routes are actually usable.
                slowLoadHandler.postDelayed(() -> {
                    if (offlineView.getVisibility() == View.VISIBLE && isOnline()) {
                        showWeb();
                        prepareForLoad();
                        webView.reload();
                    }
                }, 800);
            }
        };
        try {
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback);
        } catch (Exception e) {
            networkCallback = null; // auto-reload degrades; Try Again still works
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback == null) return;
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            cm.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {}
        networkCallback = null;
    }

    @Override
    protected void onDestroy() {
        slowLoadHandler.removeCallbacksAndMessages(null);
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                // If the error/offline screen is up, reveal the WebView again
                // so the page we navigate back to is actually visible.
                showWeb();
                webView.goBack();
            } else {
                // Keep the loaded page alive in memory instead of destroying
                // the activity — reopening the app is instant, no reload.
                moveTaskToBack(true);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
