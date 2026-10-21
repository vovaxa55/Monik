package com.example.monik;

import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    public AppManager appManager;

    // --- UI ЕЛЕМЕНТИ ---
    public TextView tvPermissionStatus, tvSortCpu, tvSortRam, tvTotalCpu;
    public TextView tvAthenaStatus, tvFreezerStatus, tvShizukuStatus;
    public Button btnOpenSettings, btnAddApp, btnMonitorMulti, btnMonitorResources, btnSettingsTab, btnStop, btnSaveIntervals, btnCreateShortcut;
    public Button btnResAll, btnResSelected, btnKillRestricted, btnLaunchAll, btnOpenShizuku, btnLanguage;
    public Button btnAthenaUninstall, btnAthenaInstall, btnFreezerDef, btnFreezerOn, btnFreezerOff;
    public AutoCompleteTextView autoCompleteApps;
    public EditText etIntervalTable, etIntervalResource;
    public ListView monitorListView, resourceListView;
    public LinearLayout tableHeader, resourceHeader, settingsContainer, addAppContainer, resourceSubTabs;

    // --- АДАПТЕРИ ---
    public ArrayAdapter<AppManager.AppItem> searchAdapter;
    public AppAdapters.MonitorAdapter monitorAdapter;
    public AppAdapters.ResourceAdapter resourceAdapter;

    // --- СТАН ДОДАТКУ ---
    public boolean isLogging = false;
    public int currentMode = 1;
    public int resourceSubMode = 0;
    public int pollingInterval = 5000;
    public int resourceInterval = 10000;
    public int sortMode = 1;
    public boolean sortDesc = true;
    public Handler handler = new Handler(Looper.getMainLooper());

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (code, res) -> {
        updateShizukuStatus();
    };
    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener = this::checkShizukuPermissionAndInit;
    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener = () -> {};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appManager = new AppManager(this);
        initUI();
        initListeners();
        loadSavedIntervals();

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        Shizuku.addBinderReceivedListener(shizukuBinderReceivedListener);
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener);

        if (Shizuku.pingBinder()) checkShizukuPermissionAndInit();
        appManager.loadInstalledApps();
        appManager.loadMonitoredApps();
        updateResourceTabsUI();

        if (getSharedPreferences("MonikPrefs", MODE_PRIVATE)
                .getBoolean("restore_settings_after_language", false)) {
            getSharedPreferences("MonikPrefs", MODE_PRIVATE).edit()
                    .putBoolean("restore_settings_after_language", false).apply();
            selectTab(5);
        }
    }

    private void initUI() {
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus);
        btnOpenSettings = findViewById(R.id.btnOpenSettings);
        btnAddApp = findViewById(R.id.btnAddApp);
        btnStop = findViewById(R.id.btnStop);
        btnMonitorMulti = findViewById(R.id.btnMonitorMulti);
        btnMonitorResources = findViewById(R.id.btnMonitorResources);
        btnSettingsTab = findViewById(R.id.btnSettingsTab);
        btnResAll = findViewById(R.id.btnResAll);
        btnResSelected = findViewById(R.id.btnResSelected);
        btnKillRestricted = findViewById(R.id.btnKillRestricted);
        btnLaunchAll = findViewById(R.id.btnLaunchAll);
        resourceSubTabs = findViewById(R.id.resourceSubTabs);
        tableHeader = findViewById(R.id.tableHeader);
        resourceHeader = findViewById(R.id.resourceHeader);
        settingsContainer = findViewById(R.id.settingsContainer);
        addAppContainer = findViewById(R.id.addAppContainer);
        autoCompleteApps = findViewById(R.id.autoCompleteApps);
        monitorListView = findViewById(R.id.monitorListView);
        resourceListView = findViewById(R.id.resourceListView);
        tvAthenaStatus = findViewById(R.id.tvAthenaStatus);
        tvFreezerStatus = findViewById(R.id.tvFreezerStatus);
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        btnOpenShizuku = findViewById(R.id.btnOpenShizuku);
        btnLanguage = findViewById(R.id.btnLanguage);
        btnAthenaUninstall = findViewById(R.id.btnAthenaUninstall);
        btnAthenaInstall = findViewById(R.id.btnAthenaInstall);
        btnFreezerDef = findViewById(R.id.btnFreezerDef);
        btnFreezerOn = findViewById(R.id.btnFreezerOn);
        btnFreezerOff = findViewById(R.id.btnFreezerOff);
        etIntervalTable = findViewById(R.id.etIntervalTable);
        etIntervalResource = findViewById(R.id.etIntervalResource);
        btnSaveIntervals = findViewById(R.id.btnSaveIntervals);
        btnCreateShortcut = findViewById(R.id.btnCreateShortcut);
        tvSortCpu = findViewById(R.id.tvSortCpu);
        tvSortRam = findViewById(R.id.tvSortRam);
        tvTotalCpu = findViewById(R.id.tvTotalCpu);

        searchAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, appManager.installedAppsList);
        autoCompleteApps.setAdapter(searchAdapter);

        monitorAdapter = new AppAdapters.MonitorAdapter(this, appManager.monitoredAppsList);
        monitorListView.setAdapter(monitorAdapter);

        resourceAdapter = new AppAdapters.ResourceAdapter(this, appManager.displayResourceList);
        resourceListView.setAdapter(resourceAdapter);
        updateLanguageButton();
    }

    private void initListeners() {
        autoCompleteApps.setOnClickListener(v -> autoCompleteApps.showDropDown());
        autoCompleteApps.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) autoCompleteApps.showDropDown(); });

        btnOpenSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        btnAddApp.setOnClickListener(v -> appManager.addAppToMonitorList());
        btnStop.setOnClickListener(v -> stopMonitoring());

        btnMonitorMulti.setOnClickListener(v -> selectTab(1));
        btnMonitorResources.setOnClickListener(v -> selectTab(4));
        btnSettingsTab.setOnClickListener(v -> selectTab(5));

        btnResAll.setOnClickListener(v -> { resourceSubMode = 0; updateResourceTabsUI(); appManager.applyResourceFilterAndSort(); });
        btnResSelected.setOnClickListener(v -> { resourceSubMode = 1; updateResourceTabsUI(); appManager.applyResourceFilterAndSort(); });
        if (btnKillRestricted != null) btnKillRestricted.setOnClickListener(v -> appManager.killAllRestrictedApps());
        if (btnLaunchAll != null) btnLaunchAll.setOnClickListener(v -> appManager.launchAllUnlaunchedApps());

        tvSortCpu.setOnClickListener(v -> { sortMode = 0; sortDesc = !sortDesc; updateResourceTabsUI(); appManager.applyResourceFilterAndSort(); });
        tvSortRam.setOnClickListener(v -> { sortMode = 1; sortDesc = !sortDesc; updateResourceTabsUI(); appManager.applyResourceFilterAndSort(); });

        btnAthenaUninstall.setOnClickListener(v -> appManager.executeSettingCommand("pm uninstall -k --user 0 com.oplus.athena", getString(R.string.athena_uninstalled_toast)));
        btnAthenaInstall.setOnClickListener(v -> appManager.executeSettingCommand("cmd package install-existing com.oplus.athena", getString(R.string.athena_restored_toast)));

        btnFreezerDef.setOnClickListener(v -> appManager.executeSettingCommand("settings delete global cached_apps_freezer", getString(R.string.freezer_default_toast)));
        btnFreezerOn.setOnClickListener(v -> appManager.executeSettingCommand("settings put global cached_apps_freezer enabled", getString(R.string.freezer_on_toast)));
        btnFreezerOff.setOnClickListener(v -> appManager.executeSettingCommand("settings put global cached_apps_freezer disabled", getString(R.string.freezer_off_toast)));

        btnSaveIntervals.setOnClickListener(v -> {
            try {
                pollingInterval = Integer.parseInt(etIntervalTable.getText().toString());
                resourceInterval = Integer.parseInt(etIntervalResource.getText().toString());
                getSharedPreferences("MonikPrefs", MODE_PRIVATE).edit()
                        .putInt("polling_interval", pollingInterval)
                        .putInt("resource_interval", resourceInterval)
                        .apply();
                Toast.makeText(this, R.string.intervals_saved, Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, R.string.input_error, Toast.LENGTH_SHORT).show(); }
        });

        btnCreateShortcut.setOnClickListener(v -> createHomeScreenShortcut());
        btnOpenShizuku.setOnClickListener(v -> openShizukuApp());
        btnLanguage.setOnClickListener(v -> showLanguageDialog());
    }

    private void updateResourceTabsUI() {
        btnResAll.setTextColor(resourceSubMode == 0 ? Color.parseColor("#2563EB") : Color.DKGRAY);
        btnResSelected.setTextColor(resourceSubMode == 1 ? Color.parseColor("#2563EB") : Color.DKGRAY);
        boolean byName = resourceSubMode == 1;
        tvSortCpu.setText(byName ? "CPU" : (sortMode == 0 ? (sortDesc ? "CPU ▼" : "CPU ▲") : "CPU"));
        tvSortRam.setText(byName ? "RAM" : (sortMode == 1 ? (sortDesc ? "RAM ▼" : "RAM ▲") : "RAM"));
        tvSortCpu.setTextColor(byName ? Color.GRAY : (sortMode == 0 ? Color.parseColor("#2563EB") : Color.DKGRAY));
        tvSortRam.setTextColor(byName ? Color.GRAY : (sortMode == 1 ? Color.parseColor("#2563EB") : Color.DKGRAY));
    }

    private void selectTab(int tabIndex) {
        currentMode = tabIndex;
        tableHeader.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        monitorListView.setVisibility(tabIndex == 1 ? View.VISIBLE : View.GONE);
        resourceSubTabs.setVisibility(tabIndex == 4 ? View.VISIBLE : View.GONE);
        resourceHeader.setVisibility(tabIndex == 4 ? View.VISIBLE : View.GONE);
        resourceListView.setVisibility(tabIndex == 4 ? View.VISIBLE : View.GONE);
        settingsContainer.setVisibility(tabIndex == 5 ? View.VISIBLE : View.GONE);
        addAppContainer.setVisibility((tabIndex == 1 || tabIndex == 4) && isLogging ? View.VISIBLE : View.GONE);
        if (btnLaunchAll != null) btnLaunchAll.setVisibility((tabIndex == 1) && isLogging ? View.VISIBLE : View.GONE);

        if (tabIndex == 5) { appManager.checkSettingsState(); refreshIntervalFields(); }
        if (tabIndex == 4 && isLogging) appManager.fetchResources();

        if (!isLogging && (tabIndex == 1 || tabIndex == 4)) startMonitoring();
    }

    private void checkShizukuPermissionAndInit() {
        appManager.executor.execute(() -> {
            if (!Shizuku.pingBinder()) return;
            try {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(100);
            } catch (Exception ignored) {}
            runOnUiThread(this::updateShizukuStatus);
        });
    }

    private void startMonitoring() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        // Usage Stats потрібен лише для вкладки "Таблиця". "Ресурси" працюють через Shizuku.
        if (currentMode != 4
                && appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName()) != AppOpsManager.MODE_ALLOWED) return;
        if (appManager.monitoredAppsList.isEmpty() && currentMode != 4) return;

        isLogging = true;
        appManager.lastCheckTime = System.currentTimeMillis();
        if (currentMode == 1) {
            addAppContainer.setVisibility(View.VISIBLE);
            if (btnLaunchAll != null) btnLaunchAll.setVisibility(View.VISIBLE);
        } else if (currentMode == 4) {
            addAppContainer.setVisibility(View.VISIBLE);
        }
        btnStop.setVisibility(View.VISIBLE);

        handler.post(monitorRunnable);
        handler.post(resourceRunnable);
    }

    private void stopMonitoring() {
        isLogging = false;
        handler.removeCallbacks(monitorRunnable);
        handler.removeCallbacks(resourceRunnable);
        btnStop.setVisibility(View.GONE);
        if (btnLaunchAll != null) btnLaunchAll.setVisibility(View.GONE);
    }

    private final Runnable monitorRunnable = new Runnable() {
        @Override public void run() {
            if (isLogging) {
                if (currentMode == 1) appManager.checkUsageEvents();
                handler.postDelayed(this, pollingInterval);
            }
        }
    };

    private final Runnable resourceRunnable = new Runnable() {
        @Override public void run() { if (isLogging) { if (currentMode == 4) appManager.fetchResources(); handler.postDelayed(this, resourceInterval); } }
    };

    @Override
    protected void onResume() {
        super.onResume();
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED) {
            tvPermissionStatus.setVisibility(View.GONE); btnOpenSettings.setVisibility(View.GONE);
        } else {
            tvPermissionStatus.setVisibility(View.VISIBLE); tvPermissionStatus.setText(R.string.usage_permission_missing); btnOpenSettings.setVisibility(View.VISIBLE);
        }
        updateShizukuStatus();
        updateLanguageButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
    }

    // --- МОВА ДОДАТКУ ---

    private boolean isEnglish() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        String tag = locales.isEmpty() ? Locale.getDefault().getLanguage() : locales.toLanguageTags();
        return tag != null && tag.toLowerCase(Locale.US).startsWith("en");
    }

    private void updateLanguageButton() {
        if (btnLanguage == null) return;
        String name = getString(isEnglish() ? R.string.language_en : R.string.language_uk);
        btnLanguage.setText(getString(R.string.language_button, name));
    }

    private void showLanguageDialog() {
        boolean english = isEnglish();
        String[] items = { getString(R.string.language_uk), getString(R.string.language_en) };
        new AlertDialog.Builder(this)
                .setTitle(R.string.language_title)
                .setSingleChoiceItems(items, english ? 1 : 0, (dialog, which) -> {
                    dialog.dismiss();
                    setAppLanguage(which == 1 ? "en" : "uk");
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setAppLanguage(String languageTag) {
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        String currentTag = current.isEmpty() ? Locale.getDefault().getLanguage() : current.toLanguageTags();
        if (currentTag != null && currentTag.toLowerCase(Locale.US).startsWith(languageTag)) {
            return;
        }
        getSharedPreferences("MonikPrefs", MODE_PRIVATE).edit()
                .putBoolean("restore_settings_after_language", true).apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag));
    }

    // --- SHIZUKU / ДІАЛОГ КЕРУВАННЯ ---

    private void updateShizukuStatus() {
        if (tvShizukuStatus == null) return;
        boolean ping = Shizuku.pingBinder();
        boolean granted = false;
        try {
            granted = ping && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {}

        if (!ping) {
            tvShizukuStatus.setText(R.string.shizuku_not_running);
            tvShizukuStatus.setTextColor(Color.parseColor("#FF0000"));
        } else if (!granted) {
            tvShizukuStatus.setText(R.string.shizuku_no_permission);
            tvShizukuStatus.setTextColor(Color.parseColor("#FFA500"));
        } else {
            tvShizukuStatus.setText(R.string.shizuku_ok);
            tvShizukuStatus.setTextColor(Color.parseColor("#00AA00"));
        }
    }

    private void openShizukuApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (intent != null) {
            try { startActivity(intent); }
            catch (Exception e) { Toast.makeText(this, R.string.shizuku_open_failed, Toast.LENGTH_SHORT).show(); }
        } else {
            Toast.makeText(this, R.string.shizuku_not_installed, Toast.LENGTH_SHORT).show();
        }
    }

    public void confirmRestrict(AppManager.ResourceApp app) {
        if (app == null) return;
        String name = (app.appName == null || app.appName.isEmpty()) ? app.pkg : app.appName;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_restrict_title, name))
                .setMessage(R.string.confirm_restrict_message)
                .setPositiveButton(R.string.confirm_restrict_positive, (d, w) -> appManager.restrictResourceApp(app.pkg))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void confirmAllow(AppManager.ResourceApp app) {
        if (app == null) return;
        String name = (app.appName == null || app.appName.isEmpty()) ? app.pkg : app.appName;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_allow_title, name))
                .setMessage(R.string.confirm_allow_message)
                .setPositiveButton(R.string.confirm_allow_positive, (d, w) -> appManager.allowResourceApp(app.pkg))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void loadSavedIntervals() {
        android.content.SharedPreferences prefs = getSharedPreferences("MonikPrefs", MODE_PRIVATE);
        pollingInterval = prefs.getInt("polling_interval", 5000);
        resourceInterval = prefs.getInt("resource_interval", 10000);
        refreshIntervalFields();
    }

    private void refreshIntervalFields() {
        etIntervalTable.setText(String.valueOf(pollingInterval));
        etIntervalResource.setText(String.valueOf(resourceInterval));
    }

        private void createHomeScreenShortcut() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                Intent shortcutIntent = new Intent(this, MainActivity.class);
                shortcutIntent.setAction(Intent.ACTION_MAIN);
                shortcutIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "monik_shortcut_" + System.currentTimeMillis())
                        .setShortLabel("Monik")
                        .setLongLabel("Monik App")
                        .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(shortcutIntent)
                        .build();

                boolean success = shortcutManager.requestPinShortcut(shortcut, null);
                if (success) {
                    Toast.makeText(this, R.string.shortcut_requested, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.shortcut_failed, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, R.string.shortcut_unsupported, Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, R.string.shortcut_android8, Toast.LENGTH_SHORT).show();
        }
    }
}