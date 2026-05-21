package com.privacydisplay;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

public class MainActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private SwitchCompat   switchMain;
    private SwitchCompat   switchAutoStart;
    private View           statusDot;
    private TextView       tvStatusLabel;
    private TextView       tvSensLabel;
    private TextView       tvDarkLabel;
    private SeekBar        seekSensitivity;
    private SeekBar        seekDarkness;
    private MaterialCardView cardPermission;

    private ActivityResultLauncher<Intent>  overlayLauncher;
    private ActivityResultLauncher<String>  notifLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applySavedTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        bindViews();
        registerLaunchers();
        loadState();
        setupListeners();
    }

    private void applySavedTheme() {
        int mode = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void bindViews() {
        switchMain      = findViewById(R.id.switchMain);
        switchAutoStart = findViewById(R.id.switchAutoStart);
        statusDot       = findViewById(R.id.statusDot);
        tvStatusLabel   = findViewById(R.id.tvStatusLabel);
        tvSensLabel     = findViewById(R.id.tvSensLabel);
        tvDarkLabel     = findViewById(R.id.tvDarkLabel);
        seekSensitivity = findViewById(R.id.seekSensitivity);
        seekDarkness    = findViewById(R.id.seekDarkness);
        cardPermission  = findViewById(R.id.cardPermission);
        findViewById(R.id.btnGrantPermission).setOnClickListener(v -> requestOverlayPerm());
    }

    private void registerLaunchers() {
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (Settings.canDrawOverlays(this)) {
                        cardPermission.setVisibility(View.GONE);
                        if (switchMain.isChecked()) startOverlayService();
                    } else {
                        switchMain.setChecked(false);
                        showSnack(getString(R.string.perm_denied));
                    }
                });

        notifLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {});
    }

    private void loadState() {
        cardPermission.setVisibility(
                Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);

        switchMain.setChecked(prefs.getBoolean(Prefs.ENABLED, false));
        switchAutoStart.setChecked(prefs.getBoolean(Prefs.AUTOSTART, false));

        // Sensitivity: threshold 15–55°, default 25°, seekbar 0–40
        int tilt = prefs.getInt(Prefs.TILT, 25);
        seekSensitivity.setMax(40);
        seekSensitivity.setProgress(tilt - 15);
        updateSensLabel(tilt);

        // Darkness: 20–100%, default 88%, seekbar 0–80
        int alpha = prefs.getInt(Prefs.ALPHA, 88);
        seekDarkness.setMax(80);
        seekDarkness.setProgress(alpha - 20);
        updateDarkLabel(alpha);

        refreshStatus();
    }

    private void setupListeners() {
        switchMain.setOnCheckedChangeListener((btn, checked) -> {
            prefs.edit().putBoolean(Prefs.ENABLED, checked).apply();
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    cardPermission.setVisibility(View.VISIBLE);
                    switchMain.setChecked(false);
                    return;
                }
                requestNotifPerm();
                startOverlayService();
            } else {
                stopOverlayService();
            }
            refreshStatus();
        });

        switchAutoStart.setOnCheckedChangeListener((btn, checked) ->
                prefs.edit().putBoolean(Prefs.AUTOSTART, checked).apply());

        seekSensitivity.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int deg = p + 15;
                updateSensLabel(deg);
                if (user) {
                    prefs.edit().putInt(Prefs.TILT, deg).apply();
                    sendUpdate();
                }
            }
        });

        seekDarkness.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int pct = p + 20;
                updateDarkLabel(pct);
                if (user) {
                    prefs.edit().putInt(Prefs.ALPHA, pct).apply();
                    sendUpdate();
                }
            }
        });
    }

    // ── Service control ───────────────────────────────────────────────────────
    private void startOverlayService() {
        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopOverlayService() {
        Intent i = new Intent(this, OverlayService.class);
        i.setAction(OverlayService.ACTION_STOP);
        startService(i);
    }

    private void sendUpdate() {
        if (OverlayService.isRunning()) {
            Intent i = new Intent(this, OverlayService.class);
            i.setAction(OverlayService.ACTION_UPDATE);
            startService(i);
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private void requestOverlayPerm() {
        overlayLauncher.launch(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void requestNotifPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────
    private void refreshStatus() {
        boolean on = prefs.getBoolean(Prefs.ENABLED, false);
        if (on) {
            statusDot.setBackgroundResource(R.drawable.bg_dot_active);
            tvStatusLabel.setText(R.string.status_active);
            tvStatusLabel.setTextColor(getColorAttr(com.google.android.material.R.attr.colorPrimary));
        } else {
            statusDot.setBackgroundResource(R.drawable.bg_dot_inactive);
            tvStatusLabel.setText(R.string.status_inactive);
            tvStatusLabel.setTextColor(getColorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        }
    }

    private void updateSensLabel(int deg) {
        String level;
        if (deg < 22)      level = getString(R.string.sens_very_high);
        else if (deg < 30) level = getString(R.string.sens_high);
        else if (deg < 40) level = getString(R.string.sens_medium);
        else               level = getString(R.string.sens_low);
        tvSensLabel.setText(getString(R.string.label_sensitivity, level, deg));
    }

    private void updateDarkLabel(int pct) {
        tvDarkLabel.setText(getString(R.string.label_darkness, pct));
    }

    private int getColorAttr(int attr) {
        int[] attrs = {attr};
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        int c = ta.getColor(0, 0);
        ta.recycle();
        return c;
    }

    private void showSnack(String msg) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show();
    }

    // ── Menu ──────────────────────────────────────────────────────────────────
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_theme_light) {
            setThemeMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (id == R.id.menu_theme_dark) {
            setThemeMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (id == R.id.menu_theme_system) {
            setThemeMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        } else if (id == R.id.menu_language) {
            showLanguageDialog();
        }
        return super.onOptionsItemSelected(item);
    }

    private void setThemeMode(int mode) {
        prefs.edit().putInt("theme_mode", mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void showLanguageDialog() {
        // Current locale tag
        LocaleListCompat currentLocales = AppCompatDelegate.getApplicationLocales();
        String currentTag = currentLocales.isEmpty() ? "" : currentLocales.get(0).toLanguageTag();
        int checkedItem = currentTag.startsWith("vi") ? 0 : 1;

        String[] langs = {"Tiếng Việt", "English"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_language)
                .setSingleChoiceItems(langs, checkedItem, (dialog, which) -> {
                    dialog.dismiss();
                    // Use AppCompatDelegate locale API — works on Android 13+ AND older via compat
                    String tag = (which == 0) ? "vi" : "en";
                    LocaleListCompat localeList = LocaleListCompat.forLanguageTags(tag);
                    AppCompatDelegate.setApplicationLocales(localeList);
                    // No need to recreate() — the framework handles it automatically
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cardPermission.setVisibility(
                Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
        refreshStatus();
    }

    abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
