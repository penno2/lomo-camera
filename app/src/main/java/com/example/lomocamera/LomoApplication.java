package com.example.lomocamera;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Ensures the Activity window has a DecorView before MainActivity's early
 * system-bar setup runs. On newer Android/GrapheneOS builds, asking the
 * Window for its InsetsController before the decor has been installed can
 * otherwise throw a NullPointerException during Activity startup.
 */
public final class LomoApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                // getDecorView() installs the decor if it has not been created yet.
                activity.getWindow().getDecorView();
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
}
