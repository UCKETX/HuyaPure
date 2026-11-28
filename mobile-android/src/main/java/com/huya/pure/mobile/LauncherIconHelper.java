package com.huya.pure.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/**
 * Keeps the Android home-screen launcher icon in sync with the device's Light/Dark mode
 * by toggling between explicit .LauncherLight and .LauncherDark activity-aliases.
 *
 * Call from Activity.onStop() (or background receivers) so the launcher icon updates
 * cleanly when leaving the app without interrupting active user interaction.
 */
public final class LauncherIconHelper {

    private static final String ALIAS_LIGHT = "com.huya.pure.mobile.LauncherLight";
    private static final String ALIAS_DARK = "com.huya.pure.mobile.LauncherDark";

    private LauncherIconHelper() {
    }

    public static boolean isSystemDarkMode(Context context) {
        if (context == null) return false;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void syncIfNeeded(Context context) {
        if (context == null) return;
        try {
            Context appCtx = context.getApplicationContext();
            PackageManager pm = appCtx.getPackageManager();
            boolean dark = isSystemDarkMode(appCtx);

            ComponentName lightComp = new ComponentName(appCtx, ALIAS_LIGHT);
            ComponentName darkComp = new ComponentName(appCtx, ALIAS_DARK);

            int lightState = pm.getComponentEnabledSetting(lightComp);
            int darkState = pm.getComponentEnabledSetting(darkComp);

            boolean lightEnabled = (lightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    || lightState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
            boolean darkEnabled = (darkState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);

            if (dark) {
                if (darkEnabled && !lightEnabled) {
                    return;
                }
                pm.setComponentEnabledSetting(
                        darkComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                );
                pm.setComponentEnabledSetting(
                        lightComp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
            } else {
                if (lightEnabled && !darkEnabled) {
                    return;
                }
                pm.setComponentEnabledSetting(
                        lightComp,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                );
                pm.setComponentEnabledSetting(
                        darkComp,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
