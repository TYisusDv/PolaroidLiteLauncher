package tv.polaroid.litehome.data.apps;

import android.content.Intent;
import android.graphics.drawable.Drawable;

/** App entry exposed by the device and launchable from the home screen. */
public final class InstalledApp {
    public final String packageName;
    public final String label;
    public final Drawable icon;
    public final Drawable banner;
    public final Intent launchIntent;

    public InstalledApp(String packageName, String label, Drawable icon, Drawable banner,
                        Intent launchIntent) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.banner = banner;
        this.launchIntent = launchIntent;
    }
}
