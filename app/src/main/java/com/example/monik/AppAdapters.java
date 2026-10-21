package com.example.monik;

import android.app.usage.UsageEvents;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AppAdapters {

    /**
     * Колір бакета з точки зору "чи може застосунок працювати у фоні":
     * зелений — дозволяє, помаранчевий — нейтрально/може обмежити, червоний — може закрити.
     */
    public static int bucketColor(int bucket) {
        if (bucket < 0) return Color.GRAY;
        if (bucket == 5 || bucket == 10) return Color.parseColor("#00A000"); // EXEMPTED / ACTIVE
        if (bucket == 20 || bucket == 30) return Color.parseColor("#FF8F00"); // WORKING_SET / FREQUENT
        if (bucket == 40 || bucket == 45) return Color.parseColor("#D00000"); // RARE / RESTRICTED
        return Color.GRAY;
    }


    public static class MonitorAdapter extends ArrayAdapter<AppManager.MonitoredApp> {
        private final MainActivity activity;

        public MonitorAdapter(MainActivity context, List<AppManager.MonitoredApp> apps) {
            super(context, 0, apps);
            this.activity = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_app_row, parent, false);

            AppManager.MonitoredApp app = getItem(position);
            ImageView imgIcon = convertView.findViewById(R.id.imgIcon);
            TextView tvAppName = convertView.findViewById(R.id.tvAppName);
            TextView tvAppInfo = convertView.findViewById(R.id.tvAppInfo);
            TextView tvState = convertView.findViewById(R.id.tvState);
            TextView tvBucket = convertView.findViewById(R.id.tvBucket);

            convertView.findViewById(R.id.btnLaunch).setVisibility(View.VISIBLE);
            convertView.findViewById(R.id.btnRemove).setVisibility(View.VISIBLE);
            convertView.findViewById(R.id.btnKillRes).setVisibility(View.GONE);
            convertView.findViewById(R.id.btnAllowRes).setVisibility(View.GONE);
            convertView.findViewById(R.id.btnRestrictRes).setVisibility(View.GONE);

            ImageView btnLaunch = convertView.findViewById(R.id.btnLaunch);
            ImageView btnRemove = convertView.findViewById(R.id.btnRemove);

            try { imgIcon.setImageDrawable(activity.getPackageManager().getApplicationIcon(app.packageName)); }
            catch (PackageManager.NameNotFoundException e) { imgIcon.setImageResource(R.drawable.ic_transparent_box); }

            tvAppName.setText(app.appName);
            String ramStatus = app.pid.isEmpty()
                    ? "<font color='#FF0000'>" + activity.getString(R.string.ram_killed) + "</font>"
                    : "<font color='#0000FF'>" + activity.getString(R.string.ram_pid, app.pid) + "</font>";
            tvAppInfo.setText(Html.fromHtml(ramStatus, Html.FROM_HTML_MODE_COMPACT));

            String timeStr = app.lastStateTime > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(app.lastStateTime)) : "--:--:--";
            String stateText = activity.getString(R.string.state_waiting);
            String stateColor = "#808080";

            if (app.activityState == UsageEvents.Event.ACTIVITY_RESUMED) { stateText = activity.getString(R.string.state_on_screen, timeStr); stateColor = "#00AA00"; }
            else if (app.activityState == UsageEvents.Event.ACTIVITY_PAUSED) { stateText = activity.getString(R.string.state_minimized, timeStr); stateColor = "#FFA500"; }
            else if (app.activityState == UsageEvents.Event.ACTIVITY_STOPPED) {
                stateText = activity.getString(app.hasForegroundService ? R.string.state_service_active : R.string.state_ui_hidden, timeStr);
                stateColor = app.hasForegroundService ? "#FF00FF" : "#FF0000";
            }

            tvState.setText(stateText);
            tvState.setTextColor(Color.parseColor(stateColor));

            tvBucket.setText(app.bucket < 0 ? "?" : String.valueOf(app.bucket));
            tvBucket.setTextColor(bucketColor(app.bucket));

            convertView.setOnClickListener(null);

            btnLaunch.setOnClickListener(v -> activity.appManager.launchAppInBackground(app.packageName));
            btnRemove.setOnClickListener(v -> {
                activity.appManager.monitoredAppsList.remove(app);
                activity.appManager.saveMonitoredApps();
                notifyDataSetChanged();
            });

            return convertView;
        }
    }

    public static class ResourceAdapter extends ArrayAdapter<AppManager.ResourceApp> {
        private final MainActivity activity;

        public ResourceAdapter(MainActivity context, List<AppManager.ResourceApp> apps) {
            super(context, 0, apps);
            this.activity = context;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_resource_row, parent, false);

            AppManager.ResourceApp app = getItem(position);
            ImageView imgIcon = convertView.findViewById(R.id.imgIcon);
            TextView tvAppName = convertView.findViewById(R.id.tvAppName);
            TextView tvAppInfo = convertView.findViewById(R.id.tvAppInfo);
            TextView tvState = convertView.findViewById(R.id.tvState);
            TextView tvBucket = convertView.findViewById(R.id.tvBucket);

            TextView btnKill = convertView.findViewById(R.id.btnKillRes);
            TextView btnAllow = convertView.findViewById(R.id.btnAllowRes);
            TextView btnRestrict = convertView.findViewById(R.id.btnRestrictRes);

            try { imgIcon.setImageDrawable(activity.getPackageManager().getApplicationIcon(app.pkg)); }
            catch (PackageManager.NameNotFoundException e) { imgIcon.setImageResource(R.drawable.ic_transparent_box); }

            String title = (app.appName == null || app.appName.isEmpty()) ? app.pkg : app.appName;
            tvAppName.setText(title);

            // Фон
            String bgText = "<font color='" + (app.isBgRestricted ? "#D00000" : "#00A000") + "'>"
                    + activity.getString(app.isBgRestricted ? R.string.bg_state_restricted : R.string.bg_state_allowed) + "</font>";

            // Сон: увімкнено = система може присипляти (фон заборонено) -> 🚫;
            // вимкнено = не присипляє (фон дозволено) -> ✅.
            String hibText = "<font color='" + (app.isHibernationEnabled ? "#D00000" : "#00A000") + "'>"
                    + activity.getString(app.isHibernationEnabled ? R.string.sleep_state_restricted : R.string.sleep_state_allowed) + "</font>";

            // WL: є виняток -> фон дозволено -> ✅; немає -> може бути обмежено -> 🚫.
            String wlText;
            if (!app.whitelisted) {
                wlText = "<font color='#D00000'><b>" + activity.getString(R.string.wl_state_denied) + "</b></font>";
            } else if ("user".equals(app.whitelistType)) {
                wlText = "<font color='#00A000'><b>" + activity.getString(R.string.wl_state_user) + "</b></font>";
            } else {
                wlText = "<font color='#00A000'><b>" + activity.getString(R.string.wl_state_sys) + "</b></font>";
            }

            String bucketHex = String.format(Locale.US, "#%06X", 0xFFFFFF & bucketColor(app.bucket));
            String bucketText = "<font color='" + bucketHex + "'><b>"
                    + activity.getString(R.string.bucket_value, app.bucket < 0 ? "?" : String.valueOf(app.bucket)) + "</b></font>";

            tvAppInfo.setText(Html.fromHtml(
                    bgText + " · " + hibText + "<br>" + wlText + " · " + bucketText,
                    Html.FROM_HTML_MODE_COMPACT));

            if (app.processCount > 0) {
                tvState.setText(String.format(Locale.US, activity.getString(R.string.cpu_ram_format), app.cpu, app.ram / 1024));
                tvState.setTextColor(Color.DKGRAY);
            } else {
                tvState.setText(R.string.process_not_running);
                tvState.setTextColor(Color.GRAY);
            }

            // Окрему колонку Bucket прибираємо — цифра вже є в рядку "Фон/Сон/WL".
            tvBucket.setVisibility(View.GONE);

            btnKill.setOnClickListener(v -> activity.appManager.killResourceApp(app.pkg));
            btnAllow.setOnClickListener(v -> activity.confirmAllow(app));
            btnRestrict.setOnClickListener(v -> activity.confirmRestrict(app));

            return convertView;
        }
    }
}