package io.sauti.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SautiBackgroundPlugin.class);
        super.onCreate(savedInstanceState);
        // Back button: let Sauti go back a page; on the first page the app goes to the background
        // instead of closing, so the music keeps playing (like Spotify).
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (SautiBackgroundPlugin.hasListeners()) SautiBackgroundPlugin.action("back", 0);
                else moveTaskToBack(true);
            }
        });
        // Android 13+: ask to show the music notification (play/pause/next on the lock screen)
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    // When the app leaves the screen, keep the web player running (music keeps going)
    // and tell the app it is in the background.
    private void keepWebViewRunning() {
        if (getBridge() == null) return;
        WebView wv = getBridge().getWebView();
        if (wv != null) { wv.onResume(); wv.resumeTimers(); }
    }

    @Override
    public void onPause() {
        super.onPause();
        SautiBackgroundPlugin.lifecycle(true);
        keepWebViewRunning();
    }

    @Override
    public void onStop() {
        super.onStop();
        SautiBackgroundPlugin.lifecycle(true);
        keepWebViewRunning();
    }

    @Override
    public void onResume() {
        super.onResume();
        SautiBackgroundPlugin.lifecycle(false);
    }
}
