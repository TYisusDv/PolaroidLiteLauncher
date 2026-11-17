package tv.polaroid.litehome.data.apps;

import java.util.List;

public interface AppsRepository {
    List<InstalledApp> getLaunchableApps();
}
