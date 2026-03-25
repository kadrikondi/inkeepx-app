package com.inkeepx.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.view.View;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends Activity {

    private WebView webView;
    private ProgressBar spinner;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout offlineView;
    private Button retryButton;
    private SensorManager sensorManager;
    private ShakeDetector shakeDetector;
    private SharedPreferences prefs;

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

        // ── Cookie persistence (must be before any page load) ─────────────────
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // ── WebView settings ──────────────────────────────────────────────────
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // ── Pull-to-refresh guard ─────────────────────────────────────────────
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) ->
            swipeRefresh.setEnabled(scrollY == 0));
        swipeRefresh.setColorSchemeColors(0xFFE8000D);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        // ── WebViewClient ─────────────────────────────────────────────────────
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("inkeepx.com")) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                spinner.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);

                // Write cookies to disk immediately
                CookieManager.getInstance().flush();

                // Track login state by URL:
                // Anywhere outside /login = user is logged in
                boolean onLoginPage = url != null && url.contains("/login");
                prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, !onLoginPage)
                    .putString(KEY_LAST_URL, onLoginPage ? LOGIN_URL : url)
                    .apply();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
        });

        // ── Progress spinner ──────────────────────────────────────────────────
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                spinner.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                if (newProgress == 100) swipeRefresh.setRefreshing(false);
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

        // ── Decide what URL to load ───────────────────────────────────────────
        if (!isOnline()) {
            showOffline();
            return;
        }

        boolean wasLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false);
        String  lastUrl     = prefs.getString(KEY_LAST_URL, LOGIN_URL);

        if (wasLoggedIn && lastUrl != null && !lastUrl.contains("/login")) {
            // Resume the last page — server will bounce to /login if the
            // session cookie has expired on its end
            webView.loadUrl(lastUrl);
        } else {
            // First launch or explicit logout — show login
            webView.loadUrl(LOGIN_URL);
        }
    }

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
        CookieManager.getInstance().flush(); // extra flush when backgrounded
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
