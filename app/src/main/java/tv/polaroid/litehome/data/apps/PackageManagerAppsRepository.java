package tv.polaroid.litehome.data.apps;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;

/** Reads launchable apps from Android's package manager. */
public final class PackageManagerAppsRepository implements AppsRepository {
    private final Context context;

    public PackageManagerAppsRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public List<InstalledApp> getLaunchableApps() {
        PackageManager pm = context.getPackageManager();
        LinkedHashMap<String, InstalledApp> found = new LinkedHashMap<>();
        collect(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), found);
        collect(pm, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), found);
        addDownloadsShortcut(pm, found);
        addMultimediaShortcut(pm, found);
        found.remove(context.getPackageName());
        ArrayList<InstalledApp> apps = new ArrayList<>(found.values());
        Collections.sort(apps, new Comparator<InstalledApp>() {
            @Override public int compare(InstalledApp a, InstalledApp b) {
                return a.label.toLowerCase(Locale.getDefault())
                        .compareTo(b.label.toLowerCase(Locale.getDefault()));
            }
        });
        return apps;
    }

    private void addDownloadsShortcut(PackageManager pm, LinkedHashMap<String, InstalledApp> found) {
        String pkg = "com.android.providers.downloads.ui";
        if (found.containsKey(pkg)) return;
        try {
            Drawable icon = pm.getApplicationIcon(pkg);
            Intent launch = new Intent("android.intent.action.VIEW_DOWNLOADS");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Drawable banner = null;
            try { banner = pm.getApplicationBanner(pkg); }
            catch (Exception ignored) { }
            found.put(pkg, new InstalledApp(pkg, "Descargas", icon, banner, launch));
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private void addMultimediaShortcut(PackageManager pm, LinkedHashMap<String, InstalledApp> found) {
        String pkg = "com.cvte.tv.media";
        if (found.containsKey(pkg)) return;
        try {
            ComponentName activity = new ComponentName(pkg, "com.cvte.tv.media.MediaCenterActivity");
            Drawable icon = pm.getActivityIcon(activity);
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.setComponent(activity);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Drawable banner = null;
            try { banner = pm.getActivityBanner(activity); }
            catch (Exception ignored) { }
            if (banner == null) try { banner = pm.getApplicationBanner(pkg); }
            catch (Exception ignored) { }
            found.put(pkg, new InstalledApp(pkg, "Multimedia", icon, banner, launch));
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private void collect(PackageManager pm, Intent query, LinkedHashMap<String, InstalledApp> found) {
        List<ResolveInfo> matches = pm.queryIntentActivities(query, 0);
        for (ResolveInfo info : matches) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if ("com.cvte.tv.androidsetting".equals(pkg)) continue;
            if (found.containsKey(pkg) || pkg.equals(context.getPackageName())) continue;
            CharSequence label = info.loadLabel(pm);
            String appLabel = label == null ? pkg : label.toString();
            ApplicationInfo application = info.activityInfo.applicationInfo;
            boolean curatedSystemApp = "com.android.providers.downloads.ui".equals(pkg)
                    || "com.cvte.tv.media".equals(pkg)
                    || "tv master".equals(normalizeLabel(appLabel));
            if (!curatedSystemApp && application != null
                    && (application.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            Drawable icon = info.loadIcon(pm);
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.setComponent(new ComponentName(pkg, info.activityInfo.name));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Drawable banner = null;
            try { banner = pm.getActivityBanner(launch); }
            catch (Exception ignored) { }
            if (banner == null) {
                try { banner = pm.getApplicationBanner(pkg); }
                catch (Exception ignored) { }
            }
            found.put(pkg, new InstalledApp(pkg, appLabel, icon, banner, launch));
        }
    }

    private String normalizeLabel(String label) {
        return Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").trim().toLowerCase(Locale.ROOT);
    }
}
