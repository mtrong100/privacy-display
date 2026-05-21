package com.privacydisplay;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

public class OverlayService extends Service implements SensorEventListener {

    public static final String ACTION_START  = "ACTION_START";
    public static final String ACTION_STOP   = "ACTION_STOP";
    public static final String ACTION_UPDATE = "ACTION_UPDATE";

    private static final String CHANNEL_ID = "privacy_display_service";
    private static final int    NOTIF_ID   = 1001;

    private static volatile boolean sRunning = false;
    public static boolean isRunning() { return sRunning; }

    private SensorManager sensorManager;
    private Sensor        accelerometer;

    private WindowManager          windowManager;
    private View                   overlayView;
    private WindowManager.LayoutParams overlayLp;
    private boolean                overlayAdded = false;
    private ValueAnimator          currentAnim  = null;
    private final Handler          mainHandler  = new Handler(Looper.getMainLooper());

    // Current animated alpha value (0.0 – 1.0)
    private float currentAlpha = 0f;

    // Settings
    private int   tiltThresholdDeg = 25;
    private float targetAlpha      = 0.95f;  // max darkness

    // Sensor: fast filter for quick response
    private final float[] lpf = new float[3];
    // α=0.25 → responsive but still smoothed (higher than before)
    private static final float LPF_A = 0.25f;

    // Hysteresis
    private boolean tilted       = false;
    private long    lastChangeMs = 0;
    // Reduced to 300ms — faster response
    private static final long MIN_STATE_MS = 300;

    // Fast fades
    private static final long FADE_IN_MS  = 200;
    private static final long FADE_OUT_MS = 180;

    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildOverlayView();
    }

    private void buildOverlayView() {
        overlayView = new View(this);
        // Pure black background
        overlayView.setBackgroundColor(0xFF000000);
        overlayView.setAlpha(0f);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.OPAQUE);  // OPAQUE = no compositing overhead, true black

        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.x = 0;
        overlayLp.y = 0;

        // KEY FIX: extend into display cutout (notch/status bar/nav bar areas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: ALWAYS extend into cutout
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        // KEY FIX: set screen brightness to minimum via WindowManager
        // -1 = use system brightness (normal), 0.0 = minimum
        // We'll animate this alongside alpha for true darkness
        overlayLp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null && intent.getAction() != null)
                ? intent.getAction() : ACTION_START;
        switch (action) {
            case ACTION_STOP:
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_UPDATE:
                reloadSettings();
                return START_STICKY;
            default:
                if (!sRunning) {
                    reloadSettings();
                    postForeground();
                    addOverlay();
                    registerSensor();
                    sRunning = true;
                }
                return START_STICKY;
        }
    }

    private void reloadSettings() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        tiltThresholdDeg = p.getInt(Prefs.TILT, 25);
        // Map slider 20-100 → alpha 0.70-1.0 so even "low" setting is noticeably dark
        int pct = p.getInt(Prefs.ALPHA, 88);
        targetAlpha = 0.70f + (pct - 20) / 80f * 0.30f;
    }

    private void addOverlay() {
        if (overlayAdded || overlayView == null) return;
        try {
            windowManager.addView(overlayView, overlayLp);
            overlayAdded = true;
        } catch (Exception e) {
            overlayAdded = false;
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────
    private void postForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_desc))
                .setSmallIcon(R.drawable.ic_notif_shield)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_action_stop, getString(R.string.notif_stop), stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
                .build();
        startForeground(NOTIF_ID, n);
    }

    // ── Sensor ────────────────────────────────────────────────────────────────
    private void registerSensor() {
        if (accelerometer == null) return;
        // SENSOR_DELAY_GAME = ~20ms between samples — fast enough for smooth response
        // maxReportLatency = 0 — deliver immediately, no batching
        // Battery impact is minimal since we unregister when not needed
        sensorManager.registerListener(this, accelerometer,
                SensorManager.SENSOR_DELAY_GAME, 0);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        lpf[0] += LPF_A * (e.values[0] - lpf[0]);
        lpf[1] += LPF_A * (e.values[1] - lpf[1]);
        lpf[2] += LPF_A * (e.values[2] - lpf[2]);

        double angle = Math.toDegrees(Math.atan2(
                Math.abs(lpf[0]),
                Math.sqrt(lpf[1] * lpf[1] + lpf[2] * lpf[2])));

        long now = System.currentTimeMillis();
        boolean shouldDarken = tilted
                ? angle >= (tiltThresholdDeg - 7)
                : angle >= tiltThresholdDeg;

        if (shouldDarken != tilted && (now - lastChangeMs) >= MIN_STATE_MS) {
            tilted       = shouldDarken;
            lastChangeMs = now;
            if (shouldDarken) mainHandler.post(this::fadeIn);
            else              mainHandler.post(this::fadeOut);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── Overlay animation ─────────────────────────────────────────────────────

    private void fadeIn() {
        if (!overlayAdded) return;
        animateAlpha(currentAlpha, targetAlpha, FADE_IN_MS);
    }

    private void fadeOut() {
        if (!overlayAdded) return;
        animateAlpha(currentAlpha, 0f, FADE_OUT_MS);
    }

    private void animateAlpha(float from, float to, long duration) {
        if (currentAnim != null && currentAnim.isRunning()) {
            currentAnim.cancel();
        }
        if (Math.abs(from - to) < 0.01f) {
            applyAlpha(to);
            return;
        }
        ValueAnimator anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            currentAlpha = v;
            applyAlpha(v);
        });
        currentAnim = anim;
        anim.start();
    }

    /**
     * Apply alpha to overlay view AND dim screen brightness simultaneously.
     * This is the key to true full-screen darkness including status/nav bars.
     */
    private void applyAlpha(float alpha) {
        if (overlayView == null || !overlayAdded) return;
        overlayView.setAlpha(alpha);

        // Also control screen brightness:
        // When fully dark (alpha=targetAlpha), brightness = 0.01 (near-black)
        // When hidden (alpha=0), brightness = normal (-1)
        if (overlayLp != null) {
            if (alpha <= 0.01f) {
                overlayLp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            } else {
                // Dim brightness proportional to overlay alpha
                // At max alpha, brightness approaches minimum (0.01)
                float dimFactor = alpha / targetAlpha; // 0.0–1.0
                overlayLp.screenBrightness = Math.max(0.01f, 1.0f - dimFactor * 0.99f);
            }
            try {
                windowManager.updateViewLayout(overlayView, overlayLp);
            } catch (Exception ignored) {}
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onDestroy() {
        sRunning = false;
        sensorManager.unregisterListener(this);
        if (currentAnim != null) currentAnim.cancel();
        if (overlayAdded && overlayView != null) {
            // Restore brightness before removing
            overlayLp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            try { windowManager.updateViewLayout(overlayView, overlayLp); } catch (Exception ignored) {}
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayAdded = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
