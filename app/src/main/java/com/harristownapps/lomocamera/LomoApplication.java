package com.harristownapps.lomocamera;

import android.app.Activity;
import android.app.Application;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small application-level helpers for Lomo Camera.
 */
public final class LomoApplication extends Application {
    private static final String TAG = "LomoCamera";
    private static final String SOFTWARE = "Lomo Camera 0.1";

    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();

        /*
         * Ensure the Activity window has a DecorView before MainActivity's early
         * system-bar setup runs. On newer Android/GrapheneOS builds, asking the
         * Window for its InsetsController before the decor has been installed can
         * otherwise throw a NullPointerException during Activity startup.
         */
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
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

        installExifObserver();
    }

    /**
     * MainActivity writes the JPEG through MediaStore and then clears IS_PENDING.
     * Watching that final MediaStore update lets us add useful EXIF metadata without
     * broad storage permissions and without touching any photos we do not own.
     */
    private void installExifObserver() {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                if (uri != null) {
                    metadataExecutor.execute(() -> writeLomoExifIfNeeded(uri));
                }
            }
        };

        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer);
    }

    private void writeLomoExifIfNeeded(Uri uri) {
        String[] projection = {
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.IS_PENDING
        };

        String name;
        String relativePath;
        long dateTaken;
        int pending;

        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            name = cursor.getString(0);
            relativePath = cursor.getString(1);
            dateTaken = cursor.getLong(2);
            pending = cursor.getInt(3);
        } catch (RuntimeException e) {
            // Most MediaStore notifications are for somebody else's media; ignore them.
            return;
        }

        if (pending != 0 || name == null || !name.startsWith("Lomo_") ||
                relativePath == null || !relativePath.startsWith("DCIM/Lomo")) {
            return;
        }

        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) return;
            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());

            // Do not keep rewriting the same file if MediaStore sends another notification.
            if (SOFTWARE.equals(exif.getAttribute(ExifInterface.TAG_SOFTWARE))) return;

            long when = dateTaken > 0 ? dateTaken : System.currentTimeMillis();
            String exifDate = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    .format(new Date(when));

            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE);
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Lomo Camera");
            exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate);
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    Integer.toString(ExifInterface.ORIENTATION_NORMAL));
            exif.saveAttributes();

            Log.i(TAG, "Added EXIF metadata to " + name);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Unable to add EXIF metadata to " + name, e);
        }
    }
}
