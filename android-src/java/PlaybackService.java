package io.sauti.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Android foreground media service: keeps Sauti alive and playing with the screen off or
 * after leaving the app, shows the music notification / lock-screen controls and handles
 * headphone and Bluetooth buttons.
 */
public class PlaybackService extends Service {

    static PlaybackService instance;

    static final String ACTION_UPDATE = "io.sauti.app.UPDATE";
    static final String ACTION_PLAY = "io.sauti.app.PLAY";
    static final String ACTION_PAUSE = "io.sauti.app.PAUSE";
    static final String ACTION_NEXT = "io.sauti.app.NEXT";
    static final String ACTION_PREV = "io.sauti.app.PREV";
    static final String ACTION_CLOSE = "io.sauti.app.CLOSE";

    private static final String CHANNEL = "sauti_playback";
    private static final int NOTIF_ID = 7301;

    private MediaSessionCompat session;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String title = "Nightwave", artist = "", artworkUrl = "";
    private Bitmap artwork;
    private boolean playing = false, started = false;
    private long durationMs = 0, positionMs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Music controls");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        session = new MediaSessionCompat(this, "Nightwave");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { SautiBackgroundPlugin.action("play", 0); }
            @Override public void onPause() { SautiBackgroundPlugin.action("pause", 0); }
            @Override public void onStop() { SautiBackgroundPlugin.action("pause", 0); }
            @Override public void onSkipToNext() { SautiBackgroundPlugin.action("next", 0); }
            @Override public void onSkipToPrevious() { SautiBackgroundPlugin.action("prev", 0); }
            @Override public void onSeekTo(long pos) { SautiBackgroundPlugin.action("seek", pos); }
        });
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nightwave::playback");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nightwave::stream");
            wifiLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_MEDIA_BUTTON.equals(a)) {           // headphone / Bluetooth buttons
            if (!started) showNotification();
            MediaButtonReceiver.handleIntent(session, intent);
        } else if (ACTION_UPDATE.equals(a)) {
            update(intent.getStringExtra("title"), intent.getStringExtra("artist"), intent.getStringExtra("artwork"),
                    intent.getBooleanExtra("playing", false), intent.getLongExtra("duration", 0), intent.getLongExtra("position", 0));
        } else if (ACTION_PLAY.equals(a)) { SautiBackgroundPlugin.action("play", 0); }
        else if (ACTION_PAUSE.equals(a)) { SautiBackgroundPlugin.action("pause", 0); }
        else if (ACTION_NEXT.equals(a)) { SautiBackgroundPlugin.action("next", 0); }
        else if (ACTION_PREV.equals(a)) { SautiBackgroundPlugin.action("prev", 0); }
        else if (ACTION_CLOSE.equals(a)) { SautiBackgroundPlugin.action("pause", 0); stopSelf(); return START_NOT_STICKY; }
        else if (!started) { showNotification(); }
        return START_NOT_STICKY;
    }

    void update(String t, String ar, String artUrl, boolean isPlaying, long dur, long pos) {
        main.post(() -> {
            title = t != null && !t.isEmpty() ? t : "Nightwave";
            artist = ar != null ? ar : "";
            playing = isPlaying; durationMs = dur; positionMs = pos;
            if (artUrl != null && !artUrl.equals(artworkUrl)) { artworkUrl = artUrl; artwork = null; loadArtwork(artUrl); }

            MediaMetadataCompat.Builder md = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Nightwave")
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs > 0 ? durationMs : -1);
            if (artwork != null) {                             // lock screen + notification picture
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork);
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork);
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork);
            }
            session.setMetadata(md.build());
            session.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE
                            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO)
                    .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED, positionMs, playing ? 1f : 0f)
                    .build());

            if (playing) {
                if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
                if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
            } else {
                if (wakeLock.isHeld()) wakeLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            }
            showNotification();
        });
    }

    private Bitmap square(Bitmap b) {
        int side = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
        return side > 512 ? Bitmap.createScaledBitmap(sq, 512, 512, true) : sq;
    }

    private void loadArtwork(String url) {
        if (url == null) return;
        if (url.startsWith("data:")) {                       // cover of a downloaded / imported song, sent by the app
            try {
                byte[] raw = android.util.Base64.decode(url.substring(url.indexOf(',') + 1), android.util.Base64.DEFAULT);
                Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (b != null) { artwork = square(b); }
            } catch (Exception ignored) { }
            return;
        }
        if (!url.startsWith("http")) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) main.post(() -> {
                        if (!url.equals(artworkUrl)) return;
                        artwork = square(b);
                        update(title, artist, artworkUrl, playing, durationMs, positionMs);
                    });
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void showNotification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sauti)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(artwork)
                .setContentIntent(content)
                .setDeleteIntent(action(ACTION_CLOSE, 5))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Previous", action(ACTION_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", action(playing ? ACTION_PAUSE : ACTION_PLAY, 2))
                .addAction(android.R.drawable.ic_media_next, "Next", action(ACTION_NEXT, 3))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (!started) {
            ServiceCompat.startForeground(this, NOTIF_ID, n,
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
            started = true;
        } else {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The app was swiped away: its player is gone too, so stop the service.
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (session != null) { session.setActive(false); session.release(); }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
