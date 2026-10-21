package com.example.monik;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

public class AppManager {

    public final MainActivity activity;
    public final UsageStatsManager usm;
    public final ExecutorService executor = Executors.newSingleThreadExecutor();

    public final ArrayList<MonitoredApp> monitoredAppsList = new ArrayList<>();
    /** Тільки запущені процеси (CPU/RAM), перебудовується кожним fetchResources(). */
    public final List<ResourceApp> fullParsedResourceList = new ArrayList<>();
    /** Те, що реально показується у вкладці "Ресурси". */
    public final ArrayList<ResourceApp> displayResourceList = new ArrayList<>();
    public final ArrayList<AppItem> installedAppsList = new ArrayList<>();

    /**
     * Кеш системних станів по пакетах: AppOps, bucket, whitelist.
     * Живе окремо від running-статистики, щоб стан не губився, коли процес убитий.
     */
    public final Map<String, ResourceApp> settingsCache = new ConcurrentHashMap<>();

    public long lastCheckTime = 0;
    public String totalCpu = "--%";

    private static final String PREFS_NAME = "MonikPrefs";
    private static final String KEY_SAVED_APPS = "saved_apps_str";

    private int bucketTick = 0;
    private boolean settingsFetchQueued = false;
    private boolean settingsDirty = false;

    // --- МОДЕЛІ ДАНИХ ---
    public static class AppItem implements Comparable<AppItem> {
        public String appName, packageName;
        public AppItem(String appName, String packageName) { this.appName = appName; this.packageName = packageName; }
        @Override public String toString() { return appName; }
        @Override public int compareTo(AppItem o) { return this.appName.toLowerCase().compareTo(o.appName.toLowerCase()); }
    }

    public static class MonitoredApp {
        public String appName, packageName, pid = "";
        public int activityState = -1;
        public volatile int bucket = -1;
        public boolean hasForegroundService = false;
        public long lastStateTime = 0;
        public MonitoredApp(String appName, String packageName) { this.appName = appName; this.packageName = packageName; }
    }

    public static class ResourceApp {
        public String pkg = "";
        public String appName = "";
        public int processCount = 0;
        public int ram = 0;
        public float cpu = 0.0f;
        public int pid = -1;

        // Системні стани
        public boolean isBgRestricted = false;
        public boolean isHibernationEnabled = true;
        public int bucket = -1;
        public boolean whitelisted = false;
        public String whitelistType = ""; // "user" | "sys" | ""
        public String opAny = "";
        public String opIn = "";
        public String opRevoke = "";

        public boolean monitored = false;
    }

    public AppManager(MainActivity activity) {
        this.activity = activity;
        this.usm = (UsageStatsManager) activity.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    // --- SHIZUKU ---

    public boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public String runShizukuCommand(String cmd) {
        if (!isShizukuReady()) return "ERR";
        try {
            Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);
            java.lang.Process process = (java.lang.Process) method.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            if (process == null) return "ERR";
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            process.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            Log.w("Monik", "shizuku command failed: " + cmd, e);
            return "ERR";
        }
    }

    // --- ПАРСИНГ РЕСУРСІВ (CPU / RAM) ---

    public void fetchResources() {
        executor.execute(() -> {
            String cpuDump = runShizukuCommand("dumpsys cpuinfo 2>/dev/null");
            String memDump = runShizukuCommand("dumpsys meminfo 2>/dev/null");

            String foundTotalCpu = "--%";
            for (String line : cpuDump.split("\n")) {
                if (line.contains("TOTAL:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 0) foundTotalCpu = parts[0];
                    break;
                }
            }
            final String finalTotalCpu = foundTotalCpu;

            HashMap<String, ResourceApp> map = new HashMap<>();

            int startIndex = memDump.indexOf("Total PSS by process:");
            if (startIndex == -1) startIndex = memDump.indexOf("Total RSS by process:");

            if (startIndex != -1) {
                int endIndex = memDump.indexOf("Total PSS by OOM adjustment:", startIndex);
                if (endIndex == -1) endIndex = memDump.indexOf("Total RSS by OOM adjustment:", startIndex);
                if (endIndex == -1) endIndex = memDump.length();

                String processBlock = memDump.substring(startIndex, endIndex);
                Pattern memPattern = Pattern.compile("([\\d,]+)K:\\s+([^\\s]+)\\s+\\(pid\\s+(\\d+)");
                Matcher memMatcher = memPattern.matcher(processBlock);
                HashSet<String> seenPids = new HashSet<>();
                while (memMatcher.find()) {
                    String pid = memMatcher.group(3);
                    if (seenPids.contains(pid)) continue;
                    seenPids.add(pid);

                    int ram = Integer.parseInt(memMatcher.group(1).replace(",", ""));
                    String basePkg = memMatcher.group(2).split(":")[0];

                    ResourceApp app = map.get(basePkg);
                    if (app == null) {
                        app = new ResourceApp();
                        app.pkg = basePkg;
                        map.put(basePkg, app);
                    }
                    app.ram += ram;
                    app.processCount++;
                    try { app.pid = Integer.parseInt(pid); } catch (Exception ignored) {}
                }
            }

            Pattern cpuPattern = Pattern.compile("\\s+([\\d.]+)%\\s+(\\d+)/([^:]+)");
            Matcher cpuMatcher = cpuPattern.matcher(cpuDump);
            while (cpuMatcher.find()) {
                float cpu = Float.parseFloat(cpuMatcher.group(1));
                String basePkg = cpuMatcher.group(3).trim().split(":")[0];
                ResourceApp app = map.get(basePkg);
                if (app == null) {
                    app = new ResourceApp();
                    app.pkg = basePkg;
                    map.put(basePkg, app);
                }
                app.cpu += cpu;
            }

            PackageManager pm = activity.getPackageManager();
            for (ResourceApp app : map.values()) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(app.pkg, 0);
                    app.appName = pm.getApplicationLabel(ai).toString();
                } catch (Exception ignored) {
                    app.appName = app.pkg;
                }
            }

            activity.runOnUiThread(() -> {
                if (!activity.isLogging) return; // після "ЗУПИНИТИ" список не смикається
                totalCpu = finalTotalCpu;
                fullParsedResourceList.clear();
                fullParsedResourceList.addAll(map.values());
                applyResourceFilterAndSort();
            });
        });
    }

    private void copyStats(ResourceApp src, ResourceApp dst) {
        dst.processCount = src.processCount;
        dst.ram = src.ram;
        dst.cpu = src.cpu;
        dst.pid = src.pid;
    }

    private static void copySettings(ResourceApp src, ResourceApp dst) {
        if (src == null) return;
        dst.isBgRestricted = src.isBgRestricted;
        dst.isHibernationEnabled = src.isHibernationEnabled;
        dst.bucket = src.bucket;
        dst.whitelisted = src.whitelisted;
        dst.whitelistType = src.whitelistType;
        dst.opAny = src.opAny;
        dst.opIn = src.opIn;
        dst.opRevoke = src.opRevoke;
    }

    private void sortResourceList(List<ResourceApp> temp) {
        Collections.sort(temp, (a, b) -> {
            int cmp;
            if (activity.sortMode == 1) cmp = Integer.compare(b.ram, a.ram);
            else cmp = Float.compare(b.cpu, a.cpu);
            if (!activity.sortDesc) cmp = -cmp;
            if (cmp == 0) {
                String na = a.appName.isEmpty() ? a.pkg : a.appName;
                String nb = b.appName.isEmpty() ? b.pkg : b.appName;
                cmp = na.compareToIgnoreCase(nb);
            }
            return cmp;
        });
    }

    public void applyResourceFilterAndSort() {
        android.app.ActivityManager am = (android.app.ActivityManager) activity.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        String ramStr = String.format(Locale.US, activity.getString(R.string.ram_free_format), mi.availMem / 1073741824.0);
        String mode = activity.getString(activity.resourceSubMode == 0 ? R.string.mode_all : R.string.mode_selected);
        activity.tvTotalCpu.setText(String.format(Locale.US, activity.getString(R.string.res_header_format), mode, totalCpu, ramStr));

        List<ResourceApp> temp = new ArrayList<>();

        if (activity.resourceSubMode == 0) {
            // Режим "Всі": тільки живі процеси, топ-20.
            for (ResourceApp src : fullParsedResourceList) {
                ResourceApp app = new ResourceApp();
                app.pkg = src.pkg;
                app.appName = src.appName;
                copyStats(src, app);
                copySettings(settingsCache.get(src.pkg), app);
                temp.add(app);
            }
            sortResourceList(temp);
            if (temp.size() > 20) temp = new ArrayList<>(temp.subList(0, 20));
        } else {
            // Режим "Обрані": всі додані в моніторинг, навіть якщо процес убитий.
            HashMap<String, ResourceApp> running = new HashMap<>();
            for (ResourceApp src : fullParsedResourceList) running.put(src.pkg, src);

            for (MonitoredApp m : monitoredAppsList) {
                ResourceApp app = new ResourceApp();
                app.pkg = m.packageName;
                app.appName = m.appName;
                app.monitored = true;
                ResourceApp r = running.get(m.packageName);
                if (r != null) copyStats(r, app);
                copySettings(settingsCache.get(m.packageName), app);
                temp.add(app);
            }
            // "Обрані" — стабільне сортування за назвою, щоб рядки не стрибали під час оновлень.
            Collections.sort(temp, (a, b) -> {
                String na = a.appName.isEmpty() ? a.pkg : a.appName;
                String nb = b.appName.isEmpty() ? b.pkg : b.appName;
                return na.compareToIgnoreCase(nb);
            });
        }

        displayResourceList.clear();
        displayResourceList.addAll(temp);
        activity.resourceAdapter.notifyDataSetChanged();
        fetchSettingsForDisplayed();
    }

    // --- ОПИТУВАННЯ СИСТЕМНИХ НАЛАШТУВАНЬ ДОДАТКІВ ---

    private String extractOpValue(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.isEmpty()) return "";
        int idx = s.lastIndexOf(':');
        if (idx >= 0) s = s.substring(idx + 1);
        return s.trim().toLowerCase(Locale.US);
    }

    private boolean isRestricted(String value) {
        return "ignore".equals(value) || "deny".equals(value);
    }

    public void fetchSettingsForDisplayed() {
        if (displayResourceList.isEmpty()) return;
        if (settingsFetchQueued) { settingsDirty = true; return; }

        settingsFetchQueued = true;
        final List<ResourceApp> snapshot = new ArrayList<>(displayResourceList);

        executor.execute(() -> {
            try {
                doFetchSettings(snapshot);
            } catch (Throwable t) {
                Log.w("Monik", "fetchSettingsForDisplayed failed", t);
            } finally {
                activity.runOnUiThread(() -> {
                    settingsFetchQueued = false;
                    if (settingsDirty) {
                        settingsDirty = false;
                        fetchSettingsForDisplayed();
                    }
                });
            }
        });
    }

    private void doFetchSettings(List<ResourceApp> snapshot) {
        // Whitelist читаємо ПЕРШИМ, щоб до розбору блоків уже знати тип винятку.
        StringBuilder cmd = new StringBuilder();
        cmd.append("echo \"###WL_BEGIN\"; dumpsys deviceidle whitelist 2>/dev/null; echo \"###WL_END\"; ");
        for (ResourceApp app : snapshot) {
            cmd.append("echo \"###").append(app.pkg).append("\"; ")
                    .append("cmd appops get ").append(app.pkg).append(" RUN_ANY_IN_BACKGROUND 2>/dev/null | tail -1; ")
                    .append("cmd appops get ").append(app.pkg).append(" RUN_IN_BACKGROUND 2>/dev/null | tail -1; ")
                    .append("cmd appops get ").append(app.pkg).append(" AUTO_REVOKE_PERMISSIONS_IF_UNUSED 2>/dev/null | tail -1; ")
                    .append("echo \"BUCKET=$(am get-standby-bucket ").append(app.pkg).append(" 2>/dev/null)\"; ");
        }

        String result = runShizukuCommand(cmd.toString());
        if ("ERR".equals(result) || result.isEmpty()) return;

        HashMap<String, String> whitelistTypes = new HashMap<>();
        HashMap<String, ResourceApp> updates = new HashMap<>();

        String currentPkg = null;
        String opAny = "", opIn = "", opRevoke = "";
        int bucket = -1;
        int opIndex = 0;
        boolean readingWhitelist = false;

        for (String rawLine : result.split("\n")) {
            String line = rawLine.trim();

            if (line.equals("###WL_BEGIN")) {
                // завершуємо останній блок
                if (currentPkg != null) {
                    applyParsedSettings(currentPkg, opAny, opIn, opRevoke, bucket, whitelistTypes, updates);
                }
                currentPkg = null;
                readingWhitelist = true;
                continue;
            }
            if (line.equals("###WL_END")) {
                readingWhitelist = false;
                continue;
            }

            if (readingWhitelist) {
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String type = parts[0].trim();
                    String pkg = parts[1].trim();
                    if (type.equals("user")) {
                        whitelistTypes.put(pkg, "user");
                    } else if ((type.equals("system") || type.equals("system-excidle"))
                            && !whitelistTypes.containsKey(pkg)) {
                        whitelistTypes.put(pkg, "sys");
                    }
                }
                continue;
            }

            if (line.startsWith("###")) {
                if (currentPkg != null) {
                    applyParsedSettings(currentPkg, opAny, opIn, opRevoke, bucket, whitelistTypes, updates);
                }
                currentPkg = line.substring(3).trim();
                opAny = ""; opIn = ""; opRevoke = "";
                bucket = -1;
                opIndex = 0;
            } else if (currentPkg != null) {
                if (line.startsWith("BUCKET=")) {
                    String v = line.substring("BUCKET=".length()).trim();
                    if (v.matches("-?\\d+")) {
                        try { bucket = Integer.parseInt(v); } catch (Exception ignored) {}
                    }
                } else {
                    if (opIndex == 0) { opAny = extractOpValue(line); opIndex = 1; }
                    else if (opIndex == 1) { opIn = extractOpValue(line); opIndex = 2; }
                    else if (opIndex == 2) { opRevoke = extractOpValue(line); opIndex = 3; }
                }
            }
        }
        if (currentPkg != null) {
            applyParsedSettings(currentPkg, opAny, opIn, opRevoke, bucket, whitelistTypes, updates);
        }

        if (updates.isEmpty()) return;

        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            for (ResourceApp app : displayResourceList) {
                ResourceApp u = updates.get(app.pkg);
                if (u != null) copySettings(u, app);
            }
            activity.resourceAdapter.notifyDataSetChanged();
        });
    }

    private void applyParsedSettings(String pkg, String opAny, String opIn, String opRevoke,
                                     int bucket, HashMap<String, String> whitelistTypes,
                                     HashMap<String, ResourceApp> updates) {
        if (pkg == null || pkg.isEmpty()) return;
        if (opAny.isEmpty() && opIn.isEmpty() && opRevoke.isEmpty() && bucket < 0) return;

        String wlType = whitelistTypes.containsKey(pkg) ? whitelistTypes.get(pkg) : "";

        ResourceApp c = settingsCache.get(pkg);
        if (c == null) {
            c = new ResourceApp();
            c.pkg = pkg;
            settingsCache.put(pkg, c);
        }
        c.opAny = opAny;
        c.opIn = opIn;
        c.opRevoke = opRevoke;
        c.isBgRestricted = isRestricted(opAny) || isRestricted(opIn);
        c.isHibernationEnabled = !isRestricted(opRevoke);
        c.bucket = bucket;
        c.whitelisted = !wlType.isEmpty();
        c.whitelistType = wlType;

        ResourceApp copy = new ResourceApp();
        copy.pkg = pkg;
        copySettings(c, copy);
        updates.put(pkg, copy);
    }

    // --- BUCKET ДЛЯ ТАБЛИЦІ МОНІТОРИНГУ ---

    public void refreshMonitoredBuckets() {
        final List<MonitoredApp> snapshot = new ArrayList<>(monitoredAppsList);
        if (snapshot.isEmpty()) return;

        executor.execute(() -> {
            StringBuilder cmd = new StringBuilder();
            for (MonitoredApp app : snapshot) {
                cmd.append("echo \"###").append(app.packageName).append("\"; ")
                        .append("am get-standby-bucket ").append(app.packageName).append(" 2>/dev/null; ");
            }
            String result = runShizukuCommand(cmd.toString());
            if ("ERR".equals(result) || result.isEmpty()) return;

            String pkg = null;
            boolean changed = false;
            for (String rawLine : result.split("\n")) {
                String line = rawLine.trim();
                if (line.startsWith("###")) {
                    pkg = line.substring(3).trim();
                } else if (pkg != null && line.matches("-?\\d+")) {
                    int b;
                    try { b = Integer.parseInt(line); } catch (Exception e) { continue; }
                    for (MonitoredApp app : snapshot) {
                        if (app.packageName.equals(pkg) && app.bucket != b) {
                            app.bucket = b;
                            changed = true;
                        }
                    }
                    pkg = null;
                }
            }
            if (changed) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing()) activity.monitorAdapter.notifyDataSetChanged();
                });
            }
        });
    }

    // --- USAGE STATS ТА ІНШЕ ---

    public void checkUsageEvents() {
        long endTime = System.currentTimeMillis();
        long startTime = activity.currentMode == 1 ? (endTime - 60000) : lastCheckTime;
        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();
        boolean needsUpdate = false;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            String pkg = event.getPackageName();
            int type = event.getEventType();
            for (MonitoredApp app : monitoredAppsList) {
                if (app.packageName.equals(pkg)) {
                    if (activity.currentMode == 1) {
                        if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) {
                            app.activityState = type; app.lastStateTime = event.getTimeStamp(); needsUpdate = true;
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (type == UsageEvents.Event.FOREGROUND_SERVICE_START) { app.hasForegroundService = true; app.lastStateTime = event.getTimeStamp(); needsUpdate = true; }
                            else if (type == UsageEvents.Event.FOREGROUND_SERVICE_STOP) { app.hasForegroundService = false; app.lastStateTime = event.getTimeStamp(); needsUpdate = true; }
                        }
                    }
                    break;
                }
            }
        }
        if (activity.currentMode == 1 && needsUpdate) activity.monitorAdapter.notifyDataSetChanged();

        if (activity.currentMode == 1) {
            executor.execute(() -> {
                boolean pidChanged = false;
                for (MonitoredApp app : monitoredAppsList) {
                    String out = runShizukuCommand("pidof -s " + app.packageName).trim();
                    String newPid = out.matches("^\\d+$") ? out : "";
                    if (!app.pid.equals(newPid)) { app.pid = newPid; pidChanged = true; }
                }
                if (pidChanged) activity.runOnUiThread(() -> {
                    if (!activity.isFinishing()) activity.monitorAdapter.notifyDataSetChanged();
                });
            });

            bucketTick++;
            if (bucketTick >= 6) {
                bucketTick = 0;
                refreshMonitoredBuckets();
            }
        }
        lastCheckTime = endTime;
    }

    public void checkSettingsState() {
        executor.execute(() -> {
            boolean isAthena = runShizukuCommand("pm list packages --user 0 | grep com.oplus.athena").contains("com.oplus.athena");
            String freezerOut = runShizukuCommand("settings get global cached_apps_freezer").trim();

            String tempText;
            int tempBtn;
            if (freezerOut.equals("disabled")) { tempText = activity.getString(R.string.freezer_not_working); tempBtn = 2; }
            else if (freezerOut.equals("enabled")) { tempText = activity.getString(R.string.freezer_working); tempBtn = 1; }
            else { tempText = activity.getString(R.string.freezer_default); tempBtn = 0; }

            final String fText = tempText;
            final int fBtn = tempBtn;
            final boolean finalIsAthena = isAthena;

            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                activity.tvAthenaStatus.setText(activity.getString(R.string.athena_status,
                        activity.getString(finalIsAthena ? R.string.athena_installed : R.string.athena_removed)));
                activity.tvFreezerStatus.setText(activity.getString(R.string.freezer_status, fText));
                activity.btnAthenaInstall.setTextColor(finalIsAthena ? android.graphics.Color.parseColor("#00AA00") : android.graphics.Color.GRAY);
                activity.btnAthenaUninstall.setTextColor(!finalIsAthena ? android.graphics.Color.parseColor("#FF0000") : android.graphics.Color.GRAY);
                activity.btnFreezerDef.setTextColor(fBtn == 0 ? android.graphics.Color.parseColor("#2563EB") : android.graphics.Color.GRAY);
                activity.btnFreezerOn.setTextColor(fBtn == 1 ? android.graphics.Color.parseColor("#00AA00") : android.graphics.Color.GRAY);
                activity.btnFreezerOff.setTextColor(fBtn == 2 ? android.graphics.Color.parseColor("#FF0000") : android.graphics.Color.GRAY);
            });
        });
    }

    public void executeSettingCommand(String cmd, String toastMsg) {
        executor.execute(() -> {
            if (!isShizukuReady()) {
                toastOnUi(activity.getString(R.string.toast_shizuku_no_command));
                return;
            }
            runShizukuCommand(cmd);
            checkSettingsState();
            toastOnUi(toastMsg);
        });
    }

    private void toastOnUi(String msg) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing()) Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        });
    }

    public void loadInstalledApps() {
        new Thread(() -> {
            PackageManager pm = activity.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo pkg : packages) {
                if ((pkg.flags & ApplicationInfo.FLAG_SYSTEM) == 0) installedAppsList.add(new AppItem(pm.getApplicationLabel(pkg).toString(), pkg.packageName));
            }
            Collections.sort(installedAppsList);
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) activity.searchAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    public void saveMonitoredApps() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (MonitoredApp app : monitoredAppsList) sb.append(app.packageName).append("|").append(app.appName).append(";;");
        prefs.edit().putString(KEY_SAVED_APPS, sb.toString()).apply();
    }

    public void loadMonitoredApps() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedData = prefs.getString(KEY_SAVED_APPS, "");
        monitoredAppsList.clear();
        if (!savedData.isEmpty()) {
            for (String appData : savedData.split(";;")) {
                String[] p = appData.split("\\|");
                if (p.length == 2) monitoredAppsList.add(new MonitoredApp(p[1], p[0]));
            }
        }
        activity.monitorAdapter.notifyDataSetChanged();
        refreshMonitoredBuckets();
    }

    public void addAppToMonitorList() {
        String typedText = activity.autoCompleteApps.getText().toString().trim();
        AppItem foundApp = null;
        for (AppItem app : installedAppsList) { if (app.appName.equalsIgnoreCase(typedText)) { foundApp = app; break; } }
        if (foundApp == null) { Toast.makeText(activity, activity.getString(R.string.toast_select_from_list), Toast.LENGTH_SHORT).show(); return; }
        for (MonitoredApp app : monitoredAppsList) {
            if (app.packageName.equals(foundApp.packageName)) {
                activity.autoCompleteApps.setText("");
                Toast.makeText(activity, activity.getString(R.string.toast_already_added), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        MonitoredApp newApp = new MonitoredApp(foundApp.appName, foundApp.packageName);
        monitoredAppsList.add(newApp);
        activity.monitorAdapter.notifyDataSetChanged();
        saveMonitoredApps();
        activity.autoCompleteApps.setText("");
        refreshMonitoredBuckets();
        if (activity.currentMode == 4) applyResourceFilterAndSort();
        Toast.makeText(activity, activity.getString(R.string.toast_added, foundApp.appName), Toast.LENGTH_SHORT).show();
    }

    public void launchAppInBackground(String packageName) {
        executor.execute(() -> {
            runShizukuCommand("monkey -p " + packageName + " -c android.intent.category.LAUNCHER 1");
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                Intent intent = new Intent(activity, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
                Toast.makeText(activity, activity.getString(R.string.toast_launched_bg), Toast.LENGTH_SHORT).show();
            });
        });
    }

    // --- ДІЇ З ФОНОМ ---

    /** Повний аналог процедури WeChat: whitelist - , AppOps ignore, bucket restricted, force-stop. */
    public void restrictResourceApp(String pkg) {
        executor.execute(() -> {
            if (!isShizukuReady()) {
                toastOnUi(activity.getString(R.string.toast_no_shizuku_access));
                return;
            }

            StringBuilder script = new StringBuilder();
            script.append("dumpsys deviceidle whitelist -").append(pkg).append(" 2>/dev/null; ")
                    .append("cmd appops set ").append(pkg).append(" RUN_ANY_IN_BACKGROUND ignore; ")
                    .append("cmd appops set ").append(pkg).append(" RUN_IN_BACKGROUND ignore; ")
                    .append("cmd appops set ").append(pkg).append(" AUTO_REVOKE_PERMISSIONS_IF_UNUSED default; ")
                    .append("am set-standby-bucket ").append(pkg).append(" restricted 2>/dev/null; ");
            if (!pkg.equals(activity.getPackageName())) {
                script.append("am force-stop ").append(pkg).append("; ");
            }
            script.append("echo \"BUCKET=$(am get-standby-bucket ").append(pkg).append(" 2>/dev/null)\"; ")
                    .append("echo \"WL=$(dumpsys deviceidle whitelist | grep -w ").append(pkg).append(" | head -1)\"");

            String out = runShizukuCommand(script.toString());
            String bucket = extractEcho(out, "BUCKET=");
            String wl = extractEcho(out, "WL=");

            String wlState = wl.isEmpty() ? "🚫" : "✅";
            String msg = activity.getString(R.string.toast_restricted, pkg, bucket.isEmpty() ? "?" : bucket, wlState);
            if (pkg.equals(activity.getPackageName())) msg += "\n" + activity.getString(R.string.toast_self_not_killed);
            else if (!bucket.isEmpty() && !"45".equals(bucket)) msg += "\n" + activity.getString(R.string.toast_bucket_override);
            toastOnUi(msg);
            Log.d("Monik", "restrict " + pkg + " => " + out.replace("\n", " | "));

            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                fetchSettingsForDisplayed();
                refreshMonitoredBuckets();
            });
        });
    }

    /** Дозволяє фон, ставить bucket active, додає в whitelist і знімає stopped. */
    public void allowResourceApp(String pkg) {
        executor.execute(() -> {
            if (!isShizukuReady()) {
                toastOnUi(activity.getString(R.string.toast_no_shizuku_access));
                return;
            }

            String script =
                    "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; " +
                    "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow; " +
                    "cmd appops set " + pkg + " AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore; " +
                    "am set-standby-bucket " + pkg + " active 2>/dev/null; " +
                    "dumpsys deviceidle whitelist +" + pkg + " 2>/dev/null; " +
                    "cmd package unstop --user 0 " + pkg + " 2>/dev/null; " +
                    "echo \"BUCKET=$(am get-standby-bucket " + pkg + " 2>/dev/null)\"; " +
                    "echo \"WL=$(dumpsys deviceidle whitelist | grep -w " + pkg + " | head -1)\"";

            String out = runShizukuCommand(script);
            String bucket = extractEcho(out, "BUCKET=");
            String wl = extractEcho(out, "WL=");

            String wlState = wl.isEmpty() ? "🚫" : "✅";
            String msg = activity.getString(R.string.toast_allowed, pkg, bucket.isEmpty() ? "?" : bucket, wlState);
            toastOnUi(msg);
            Log.d("Monik", "allow " + pkg + " => " + out.replace("\n", " | "));

            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                fetchSettingsForDisplayed();
                refreshMonitoredBuckets();
            });
        });
    }

    private String extractEcho(String out, String prefix) {
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    public void setWhitelist(String pkg, boolean add) {
        executor.execute(() -> {
            if (!isShizukuReady()) {
                toastOnUi(activity.getString(R.string.toast_no_shizuku_access));
                return;
            }
            String out = runShizukuCommand("dumpsys deviceidle whitelist " + (add ? "+" : "-") + pkg);
            String msg = activity.getString(add ? R.string.toast_wl_added : R.string.toast_wl_removed, pkg);
            if (!out.isEmpty()) msg += "\n" + out;
            toastOnUi(msg);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                fetchSettingsForDisplayed();
                refreshMonitoredBuckets();
            });
        });
    }

    public void unstopPackage(String pkg) {
        executor.execute(() -> {
            if (!isShizukuReady()) {
                toastOnUi(activity.getString(R.string.toast_no_shizuku_access));
                return;
            }
            String out = runShizukuCommand("cmd package unstop --user 0 " + pkg + " 2>&1").trim();
            String msg = activity.getString(R.string.toast_unstop, pkg);
            if (!out.isEmpty()) msg += "\n" + out;
            toastOnUi(msg);
        });
    }

    public void killResourceApp(String pkg) {
        executor.execute(() -> {
            runShizukuCommand("am force-stop " + pkg);
            toastOnUi(activity.getString(R.string.toast_killed, pkg));
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                fetchSettingsForDisplayed();
                refreshMonitoredBuckets();
            });
        });
    }

    public void launchAllUnlaunchedApps() {
        executor.execute(() -> {
            int launchedCount = 0;
            for (MonitoredApp app : monitoredAppsList) {
                if (app.pid == null || app.pid.isEmpty()) {
                    runShizukuCommand("monkey -p " + app.packageName + " -c android.intent.category.LAUNCHER 1");
                    launchedCount++;
                }
            }
            final int count = launchedCount;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) return;
                Intent intent = new Intent(activity, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                activity.startActivity(intent);
                Toast.makeText(activity, activity.getString(R.string.toast_launched_count, count), Toast.LENGTH_SHORT).show();
                refreshMonitoredBuckets();
            });
        });
    }

    public void killAllRestrictedApps() {
        executor.execute(() -> {
            int killedCount = 0;
            List<ResourceApp> listToProcess = new ArrayList<>(fullParsedResourceList);
            for (ResourceApp app : listToProcess) {
                ResourceApp cached = settingsCache.get(app.pkg);
                boolean restricted = cached != null ? cached.isBgRestricted : app.isBgRestricted;
                if (restricted) {
                    runShizukuCommand("am force-stop " + app.pkg);
                    killedCount++;
                }
            }
            final int count = killedCount;
            toastOnUi(activity.getString(R.string.toast_killed_count, count));
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && activity.currentMode == 4) fetchResources();
            });
        });
    }
}
