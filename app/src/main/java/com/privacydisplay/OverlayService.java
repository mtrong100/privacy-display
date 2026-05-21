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

    private WindowManager              windowManager;
    private View                       overlayView;
    private WindowManager.LayoutParams overlayLp;
    private boolean                    overlayAdded = false;
    private ValueAnimator              currentAnim  = null;
    private final Handler              mainHandler  = new Handler(Looper.getMainLooper());

    // Tracks actual displayed alpha (updated during animation)
    private float currentAlpha = 0f;

    // Settings
    private int   tiltThresholdDeg = 25;
    // Max alpha: 0.78 = noticeably dark but not pitch black
    // Slider maps 20–100% → 0.55–0.82f
    private float targetAlpha = 0.78f;

    // Sensor low-pass filter
    private final float[] lpf = new float[3];
    private static final float LPF_A = 0.25f;
    // BUG FIX: seed flag — don't evaluate tilt until we have real sensor data
    private boolean lpfReady = false;
    private int     lpfWarmup = 0; // count samples before trusting lpf

    // Hysteresis
    private boolean tilted       = false;
    private long    lastChangeMs = 0;
    private static final long MIN_STATE_MS = 300;

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
        overlayView.setBackgroundColor(0xFF000000);
        // Start fully transparent — no darkness at all until sensor confirms tilt
        overlayView.setAlpha(0f);
        currentAlpha = 0f;

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
                PixelFormat.TRANSLUCENT);

        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.x = 0;
        overlayLp.y = 0;

        // Cover display cutout (notch), status bar, and nav bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // Screen brightness: NONE = don't override (leave at system level)
        // We only dim screen when overlay is actually shown
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
        // Slider 20–100 maps to alpha 0.55–0.82
        // 0.55 = visible but not too dark; 0.82 = comfortably dark without screen brightness trick
        int pct = p.getInt(Prefs.ALPHA, 88);
        targetAlpha = 0.65f + (pct - 20) / 80f * 0.27f;
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
        sensorManager.registerListener(this, accelerometer,
                SensorManager.SENSOR_DELAY_GAME, 0);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // BUG FIX: seed lpf with first real reading so phone starts "upright"
        // instead of treating 0,0,0 as valid and computing wrong angle
        if (!lpfReady) {
            lpf[0] = e.values[0];
            lpf[1] = e.values[1];
            lpf[2] = e.values[2];
            lpfWarmup++;
            // Wait for 5 samples (~100ms at SENSOR_DELAY_GAME) before trusting data
            if (lpfWarmup >= 5) lpfReady = true;
            return; // don't evaluate tilt during warmup
        }

        lpf[0] += LPF_A * (e.values[0] - lpf[0]);
        lpf[1] += LPF_A * (e.values[1] - lpf[1]);
        lpf[2] += LPF_A * (e.values[2] - lpf[2]);

        // Tilt angle from vertical: 0° = upright, 90° = lying flat/sideways
        double angle = Math.toDegrees(Math.atan2(
                Math.abs(lpf[0]),
                Math.sqrt(lpf[1] * lpf[1] + lpf[2] * lpf[2])));

        long now = System.currentTimeMillis();

        // Hysteresis: wider gap on the OFF side prevents oscillation at boundary
        boolean shouldDarken = tilted
                ? angle >= (tiltThresholdDeg - 7)   // stay dark if still somewhat tilted
                : angle >= tiltThresholdDeg;          // go dark only when clearly tilted

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

    private void applyAlpha(float alpha) {
        if (overlayView == null || !overlayAdded) return;
        overlayView.setAlpha(alpha);

        // Dim screen brightness proportionally — covers status bar & nav bar backlight
        // Only active when overlay is visible (alpha > 0)
        if (overlayLp != null) {
            if (alpha < 0.02f) {
                // Fully hidden — restore system brightness
                overlayLp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            } else {
                // Gentle brightness dim: at max alpha → brightness 0.15 (not pitch black)
                // This fills the gap left by status/nav bars without being too extreme
                float ratio = alpha / targetAlpha; // 0.0–1.0
                overlayLp.screenBrightness = Math.max(0.08f, 1.0f - ratio * 0.92f);
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
            // Always restore brightness on exit
            overlayLp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            try { windowManager.updateViewLayout(overlayView, overlayLp); } catch (Exception ignored) {}
            try { windowManager.removeView(overlayView); } catch (Exception ignored) {}
            overlayAdded = false;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
