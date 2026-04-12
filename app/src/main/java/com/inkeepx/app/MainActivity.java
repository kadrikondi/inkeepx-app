package com.inkeepx.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (ext == null) ext = "bin";
            String fileName = "inkeepx_export_" + System.currentTimeMillis() + "." + ext;

            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getCacheDir();
            File file = new File(dir, fileName);

            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }

            Uri fileUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);

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
            Toast.makeText(this, "Saved: " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
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
