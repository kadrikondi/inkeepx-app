package com.inkeepx.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar spinner;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout offlineView;
    private Button retryButton;
    private SensorManager sensorManager;
    private ShakeDetector shakeDetector;
    private SharedPreferences prefs;

    // File chooser callback — held so we can deliver the result from onActivityResult
    private ValueCallback<Uri[]> fileChooserCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int CAMERA_PERMISSION_REQUEST = 1002;

    private static final String LOGIN_URL     = "https://www.inkeepx.com/login";
    private static final String PREFS_NAME    = "inkeepx_session";
    private static final String KEY_LAST_URL  = "last_url";
    private static final String KEY_LOGGED_IN = "logged_in";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs        = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        spinner      = findViewById(R.id.spinner);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView      = findViewById(R.id.webView);
        offlineView  = findViewById(R.id.offlineView);
        retryButton  = findViewById(R.id.retryButton);

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

        // ── Pull-to-refresh guard ─────────────────────────────────────────────
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) ->
            swipeRefresh.setEnabled(scrollY == 0));
        swipeRefresh.setColorSchemeColors(0xFFE8000D);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

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
                String url = request.getUrl().toString();
                if (isLikelyCsvDownload(url, null, null)) {
                    handleAuthenticatedWebDownload(url);
                    return true;
                }
                if (url.contains("inkeepx.com")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                spinner.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                CookieManager.getInstance().flush();

                // Track login state
                boolean onLoginPage = url != null && url.contains("/login");
                prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, !onLoginPage)
                    .putString(KEY_LAST_URL, onLoginPage ? LOGIN_URL : url)
                    .apply();

                // Patch window.print() to route through Android PrintManager
                view.evaluateJavascript(
                    "window.print = function() { AndroidPrint.print(); };", null);

                // Patch download flows (including <a download> + blob:) for WebView.
                injectDownloadCompatScript();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
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

                // Also add a camera capture option for image fields
                Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);

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
        });

        // ── Shake to reload ───────────────────────────────────────────────────
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        shakeDetector = new ShakeDetector(() -> runOnUiThread(() ->
            new AlertDialog.Builder(this)
                .setMessage("Reload page?")
                .setPositiveButton("Yes", (d, w) -> webView.reload())
                .setNegativeButton("No",  (d, w) -> d.dismiss())
                .show()));

        // ── Retry button ──────────────────────────────────────────────────────
        retryButton.setOnClickListener(v -> {
            if (isOnline()) {
                showWeb();
                webView.reload();
            } else {
                offlineView.animate().alpha(0.5f).setDuration(100)
                    .withEndAction(() ->
                        offlineView.animate().alpha(1f).setDuration(100).start())
                    .start();
            }
        });

        // ── Initial URL ───────────────────────────────────────────────────────
        if (!isOnline()) {
            showOffline();
            return;
        }

        boolean wasLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false);
        String  lastUrl     = prefs.getString(KEY_LAST_URL, LOGIN_URL);

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
                if (data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{ Uri.parse(dataString) };
                    } else if (data.getClipData() != null) {
                        // Multiple files selected
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
            }
            // Deliver result (null = cancelled, which is also correct behaviour)
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
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

    // ── Blob / data: URI download ─────────────────────────────────────────────
    private void handleBlobOrDataDownload(String url, String contentDisposition,
                                          String mimeType) {
        String safeUrl = url.replace("'", "\\'");
        String js =
            "(function() {" +
            "  var url = '" + safeUrl + "';" +
            "  if (url.startsWith('data:')) {" +
            "    var base64 = url.split(',')[1];" +
            "    var mime = url.split(';')[0].split(':')[1];" +
            "    AndroidDownload.receiveBase64(base64, mime);" +
            "    return;" +
            "  }" +
            "  fetch(url)" +
            "    .then(r => r.blob())" +
            "    .then(blob => {" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function() {" +
            "        var b64 = reader.result.split(',')[1];" +
            "        AndroidDownload.receiveBase64(b64, blob.type);" +
            "      };" +
            "      reader.readAsDataURL(blob);" +
            "    });" +
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
            "  function sendBlob(blob) {" +
            "    var fr = new FileReader();" +
            "    fr.onloadend = function() {" +
            "      var d = String(fr.result || '');" +
            "      var i = d.indexOf(',');" +
            "      var b64 = i >= 0 ? d.substring(i + 1) : '';" +
            "      AndroidDownload.receiveBase64(b64, blob.type || 'text/csv');" +
            "    };" +
            "    fr.readAsDataURL(blob);" +
            "  }" +
            "" +
            "  function handleHref(href) {" +
            "    if (!href) return false;" +
            "    var u = toAbsUrl(href);" +
            "    if (u.indexOf('blob:') === 0 && map[u]) {" +
            "      if (map[u].b64) {" +
            "        AndroidDownload.receiveBase64(map[u].b64, map[u].mime || 'text/csv');" +
            "      } else if (map[u].blob) {" +
            "        sendBlob(map[u].blob);" +
            "      } else {" +
            "        return false;" +
            "      }" +
            "      return true;" +
            "    }" +
            "    if (u.indexOf('data:') === 0) {" +
            "      var p = u.split(',');" +
            "      var meta = p[0] || '';" +
            "      var b64 = p[1] || '';" +
            "      var m = (meta.split(';')[0] || '').replace('data:', '') || 'text/csv';" +
            "      AndroidDownload.receiveBase64(b64, m);" +
            "      return true;" +
            "    }" +
            "    if (u.indexOf('.csv') >= 0 || u.indexOf('format=csv') >= 0) {" +
            "      fetch(u, { credentials: 'include' })" +
            "        .then(function(r) { return r.blob(); })" +
            "        .then(sendBlob)" +
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
            "    if (handleHref(href)) {" +
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
            "        if (handleHref(href)) return;" +
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
        android.app.DownloadManager.Request request =
            new android.app.DownloadManager.Request(Uri.parse(url));
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        request.setTitle(fileName);
        request.setDescription("Downloading via InkeepX");
        request.setMimeType(mimeType);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) request.addRequestHeader("Cookie", cookies);
        request.addRequestHeader("User-Agent", userAgent);
        request.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        android.app.DownloadManager dm =
            (android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(request);
        Toast.makeText(this, "Downloading " + fileName + "…", Toast.LENGTH_SHORT).show();
    }

    // ── Authenticated web download (keeps site session/cookies) ──────────────
    private void handleAuthenticatedWebDownload(String url) {
        String safeUrl = url.replace("'", "\\'");
        String js =
            "(function() {" +
            "  fetch('" + safeUrl + "', { credentials: 'include' })" +
            "    .then(function(r) {" +
            "      if (!r.ok) throw new Error('HTTP ' + r.status);" +
            "      return r.blob();" +
            "    })" +
            "    .then(function(blob) {" +
            "      var reader = new FileReader();" +
            "      reader.onloadend = function() {" +
            "        var b64 = reader.result.split(',')[1];" +
            "        AndroidDownload.receiveBase64(b64, blob.type || 'text/csv');" +
            "      };" +
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
            runOnUiThread(() -> saveBase64File(base64, mimeType));
        }
    }

    private void saveBase64File(String base64, String mimeType) {
        try {
            if (base64 == null || base64.isEmpty()) {
                Toast.makeText(this, "Download failed. Please try again.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (mimeType == null || mimeType.trim().isEmpty()) mimeType = "application/octet-stream";
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (ext == null) ext = mimeType.contains("csv") ? "csv" : "bin";
            String fileName = "inkeepx_export_" + System.currentTimeMillis() + "." + ext;
            byte[] data = Base64.decode(base64, Base64.DEFAULT);

            Uri fileUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                fileUri = saveToPublicDownloads(fileName, mimeType, data);
            } else {
                fileUri = saveToAppExternalFiles(fileName, data);
            }

            openDownloadedFile(fileUri, mimeType);
            Toast.makeText(this, "Saved to Downloads: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

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
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Open with"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isOnline() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo net = cm.getActiveNetworkInfo();
        return net != null && net.isConnected();
    }

    private void showOffline() {
        spinner.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        sensorManager.unregisterListener(shakeDetector);
        CookieManager.getInstance().flush();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
