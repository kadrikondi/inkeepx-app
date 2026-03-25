package com.inkeepx.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
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
    private static final String APP_URL = "https://www.inkeepx.com/login";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        spinner = findViewById(R.id.spinner);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView = findViewById(R.id.webView);
        offlineView = findViewById(R.id.offlineView);
        retryButton = findViewById(R.id.retryButton);

        retryButton.setOnClickListener(v -> {
            if (isOnline()) {
                showWeb();
                webView.reload();
            } else {
                // subtle shake feedback — just re-show offline so user knows we checked
                offlineView.animate().alpha(0.5f).setDuration(100)
                    .withEndAction(() -> offlineView.animate().alpha(1f).setDuration(100).start())
                    .start();
            }
        });

        // Only enable pull-to-refresh when the PAGE itself is scrolled to top
        // This won't be fooled by internal scrollable elements like product lists
        webView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            swipeRefresh.setEnabled(scrollY == 0);
        });

        swipeRefresh.setColorSchemeColors(0xFFE8000D);
        swipeRefresh.setOnRefreshListener(() -> webView.reload());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("inkeepx.com")) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                spinner.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOffline();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    spinner.setVisibility(View.VISIBLE);
                } else {
                    spinner.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                }
            }
        });

        // Shake to refresh — shows a confirm dialog before reloading
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        shakeDetector = new ShakeDetector(() -> {
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                    .setMessage("Reload page?")
                    .setPositiveButton("Yes", (dialog, which) -> webView.reload())
                    .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                    .show();
            });
        });

        webView.loadUrl(APP_URL);
        if (!isOnline()) {
            showOffline();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
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
