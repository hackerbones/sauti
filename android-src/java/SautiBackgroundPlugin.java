package io.sauti.app;

import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between the Sauti web player and Android's background media service. */
@CapacitorPlugin(name = "SautiBackground")
public class SautiBackgroundPlugin extends Plugin {

    private static SautiBackgroundPlugin instance;

    @Override
    public void load() { instance = this; }

    static boolean hasListeners() { return instance != null && instance.hasListeners("action"); }

    static void lifecycle(boolean hidden) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("hidden", hidden);
        instance.notifyListeners("lifecycle", o, true);
    }

    static void action(String action, long positionMs) {
        if (instance == null) return;
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("position", positionMs / 1000.0);
        instance.notifyListeners("action", o, true);
    }

    @PluginMethod
    public void update(PluginCall call) {
        String title = call.getString("title", "Sauti");
        String artist = call.getString("artist", "");
        String artwork = call.getString("artwork", "");
        boolean playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
        double duration = call.getDouble("duration", 0.0);
        double position = call.getDouble("position", 0.0);

        if (PlaybackService.instance != null) {
            PlaybackService.instance.update(title, artist, artwork, playing, (long) (duration * 1000), (long) (position * 1000));
        } else if (playing) {
            // First play: start the foreground media service (the app is on screen at this point)
            Intent i = new Intent(getContext(), PlaybackService.class);
            i.setAction(PlaybackService.ACTION_UPDATE);
            i.putExtra("title", title);
            i.putExtra("artist", artist);
            i.putExtra("artwork", artwork);
            i.putExtra("playing", true);
            i.putExtra("duration", (long) (duration * 1000));
            i.putExtra("position", (long) (position * 1000));
            try {
                ContextCompat.startForegroundService(getContext(), i);
            } catch (Exception e) {
                call.reject("Could not start background playback: " + e.getMessage());
                return;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void moveToBack(PluginCall call) {
        getActivity().runOnUiThread(() -> getActivity().moveTaskToBack(true));
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        getContext().stopService(new Intent(getContext(), PlaybackService.class));
        call.resolve();
    }
}
