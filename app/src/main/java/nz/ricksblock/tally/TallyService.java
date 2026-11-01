package nz.ricksblock.tally;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;

/**
 * Runs during a shift with the screen off.
 * Listens for the Bluetooth clicker (it acts like a volume or media button),
 * reads GPS, and records one tree per click.
 */
public class TallyService extends Service {
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_TAP = "tap";
    private static final String CHANNEL = "shift";
    private static final int NOTE_ID = 1;

    public static volatile boolean running = false;

    private LocationManager lm;
    private Location fix;
    private MediaSession session;
    private AudioTrack silence;
    private PowerManager.WakeLock wake;
    private ToneGenerator tone;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastPress = 0;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            if (fix == null || LocationManager.GPS_PROVIDER.equals(l.getProvider()) || l.getAccuracy() < fix.getAccuracy()) fix = l;
            prefs().edit().putFloat("gps_acc", l.getAccuracy()).putLong("gps_at", System.currentTimeMillis()).apply();
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private final Runnable uploader = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                String s = Store.upload(getApplicationContext());
                prefs().edit().putString("sync", s).apply();
            }).start();
            handler.postDelayed(this, 60_000);
        }
    };

    private SharedPreferences prefs() { return Store.prefs(this); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) { stopShift(); return START_NOT_STICKY; }
        if (ACTION_TAP.equals(action)) { if (running) press(); return START_STICKY; }
        if (!running) startShift();
        return START_STICKY;
    }

    private void startShift() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Shift", NotificationManager.IMPORTANCE_LOW));
        Notification n = note("Shift running. Click once per tree.");
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTE_ID, n);
        running = true;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tally:shift");
        wake.acquire(14L * 3600 * 1000);

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, listener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 0, listener, Looper.getMainLooper());
        } catch (SecurityException e) {
            prefs().edit().putString("sync", "Location not allowed").apply();
        }

        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80); } catch (Exception e) { tone = null; }
        startSilence();
        startSession();
        handler.postDelayed(uploader, 10_000);
    }

    private void stopShift() {
        running = false;
        handler.removeCallbacks(uploader);
        new Thread(() -> {
            String s = Store.upload(getApplicationContext());
            prefs().edit().putString("sync", s).apply();
        }).start();
        if (lm != null) lm.removeUpdates(listener);
        if (session != null) { session.setActive(false); session.release(); session = null; }
        if (silence != null) { try { silence.stop(); } catch (Exception ignored) {} silence.release(); silence = null; }
        if (tone != null) { tone.release(); tone = null; }
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { if (running) stopShift(); super.onDestroy(); }

    /** A silent sound loop keeps this app first in line for button presses while the screen is off. */
    private void startSilence() {
        try {
            int rate = 8000, frames = rate; // 1 second
            silence = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build();
            silence.write(new short[frames], 0, frames);
            silence.setLoopPoints(0, frames, -1);
            silence.play();
        } catch (Exception e) {
            silence = null;
        }
    }

    private void startSession() {
        session = new MediaSession(this, "tally");
        session.setCallback(new MediaSession.Callback() {
            @Override public boolean onMediaButtonEvent(Intent i) {
                KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (k != null && k.getAction() == KeyEvent.ACTION_DOWN && k.getRepeatCount() == 0) press();
                return true;
            }
            @Override public void onPlay() { press(); }
            @Override public void onPause() { press(); }
        });
        // Clicker "volume up" presses come here instead of changing the volume.
        session.setPlaybackToRemote(new VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
            @Override public void onAdjustVolume(int direction) { if (direction != 0) press(); }
        });
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build());
        session.setActive(true);
    }

    /** One click = one tree. */
    private void press() {
        long now = System.currentTimeMillis();
        if (now - lastPress < 350) return; // ignore double bounces
        lastPress = now;
        Location l = fix;
        boolean ok = l != null && (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) < 60_000_000_000L;
        String task = prefs().getString("task", "plant");
        int n = Store.addTap(this, task, ok ? l.getLatitude() : 0, ok ? l.getLongitude() : 0, ok ? l.getAccuracy() : 0, ok);
        feedback(ok);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTE_ID, note(Store.taskName(task) + ": " + n + " today" + (ok ? "" : " (last one had no GPS)")));
    }

    private void feedback(boolean ok) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (ok) v.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(VibrationEffect.createWaveform(new long[]{0, 70, 90, 70}, -1));
            }
        } catch (Exception ignored) {}
        if (tone != null && prefs().getBoolean("beep", true)) {
            try { tone.startTone(ok ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_NACK, ok ? 120 : 300); } catch (Exception ignored) {}
        }
    }

    private Notification note(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Tree Tally")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
