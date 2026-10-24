package info.varden.hauk.streams;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import info.varden.hauk.Constants;
import info.varden.hauk.utils.Log;
import info.varden.hauk.utils.TimeUtils;

/**
 * Reads the public live stream links of the cameras of the RemoteCam account the RemoteCam app on
 * this phone is logged in to, through its StreamLinksProvider.
 *
 * <p>The links are read when {@link #start(Listener)} is called and then every
 * {@link #UPDATE_INTERVAL_MILLIS} milliseconds, so that cameras that are added or removed, or links
 * that could not be fetched by RemoteCam at first, reach the viewers while sharing.</p>
 */
public final class RemoteCamStreamSource {
    private static final String AUTHORITY_SUFFIX = ".provider.streams";
    private static final String PERMISSION_SUFFIX = ".permission.READ_STREAM_LINKS";
    private static final String PATH_LINKS = "links";
    private static final String PARAM_REFRESH = "refresh";

    private static final String COLUMN_CAMERA_NAME = "camera_name";
    private static final String COLUMN_URL = "url";

    private static final String[] LINKS_PROJECTION = {COLUMN_CAMERA_NAME, COLUMN_URL};

    /**
     * How often the stream links are read and reported to the listener: every 10 seconds.
     */
    private static final long UPDATE_INTERVAL_MILLIS = 10 * TimeUtils.MILLIS_PER_SECOND;

    /**
     * Keys of the camera objects in the JSON array sent to the backend.
     */
    private static final String JSON_NAME = "name";
    private static final String JSON_URL = "url";

    /**
     * Android application context.
     */
    private final Context ctx;

    /**
     * The URI of the stream links. RemoteCam answers it from its cache, and fetches the links from
     * its server in the background if they are older than 15 seconds.
     */
    private final Uri linksUri;

    /**
     * The URI of the stream links that makes RemoteCam fetch them from its server first. Used for
     * the first query, so that sharing starts with current links.
     */
    private final Uri refreshLinksUri;

    /**
     * The thread on which the stream links are queried, or null if the source is stopped.
     */
    @Nullable
    private HandlerThread thread = null;

    private RemoteCamStreamSource(Context ctx, String packageName) {
        this.ctx = ctx.getApplicationContext();
        this.linksUri = Uri.parse("content://" + packageName + AUTHORITY_SUFFIX).buildUpon() //NON-NLS
                .appendPath(PATH_LINKS)
                .build();
        this.refreshLinksUri = this.linksUri.buildUpon()
                .appendQueryParameter(PARAM_REFRESH, "1")
                .build();
    }

    /**
     * Receives the stream links read from RemoteCam.
     */
    @FunctionalInterface
    public interface Listener {
        /**
         * Called on a background thread whenever the stream links have been read.
         *
         * @param streams The stream links as a JSON array of objects with the camera name and the
         *                URL of its stream page.
         */
        void onStreamsRead(@NonNull String streams);
    }

    /**
     * Creates a stream source for the first installed RemoteCam build that Hauk has been granted
     * access to.
     *
     * @param ctx Android application context.
     * @return A stream source, or null if no RemoteCam build is installed or none have granted
     *         access to their stream links.
     */
    @Nullable
    public static RemoteCamStreamSource create(Context ctx) {
        for (String pkg : getInstalledPackages(ctx)) {
            if (ctx.checkSelfPermission(getPermission(pkg)) == PackageManager.PERMISSION_GRANTED) {
                Log.i("Using RemoteCam stream links from %s", pkg); //NON-NLS
                return new RemoteCamStreamSource(ctx, pkg);
            }
        }
        Log.w("No RemoteCam installation with granted stream links permission found"); //NON-NLS
        return null;
    }

    /**
     * Returns the package names of all installed RemoteCam builds that expose stream links, in
     * order of preference.
     *
     * @param ctx Android application context.
     */
    @NonNull
    public static List<String> getInstalledPackages(Context ctx) {
        List<String> installed = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : Constants.REMOTECAM_PACKAGES) {
            if (pm.resolveContentProvider(pkg + AUTHORITY_SUFFIX, 0) != null) installed.add(pkg);
        }
        return installed;
    }

    /**
     * Returns the runtime permission required to read stream links from the given RemoteCam build.
     *
     * @param packageName The package name of the RemoteCam build.
     */
    @NonNull
    public static String getPermission(String packageName) {
        return packageName + PERMISSION_SUFFIX;
    }

    /**
     * Starts reading the stream links in the background, right away and then periodically.
     * RemoteCam fetches them from its server for the first read, which can take a few seconds.
     *
     * @param listener The listener that receives the stream links.
     */
    public void start(Listener listener) {
        this.thread = new HandlerThread("RemoteCamStreamSource"); //NON-NLS
        this.thread.start();
        Handler handler = new Handler(this.thread.getLooper());
        handler.post(new Runnable() {
            private boolean refresh = true;

            @Override
            public void run() {
                String streams = query(this.refresh ? RemoteCamStreamSource.this.refreshLinksUri : RemoteCamStreamSource.this.linksUri);
                this.refresh = false;
                if (streams != null) listener.onStreamsRead(streams);
                handler.postDelayed(this, UPDATE_INTERVAL_MILLIS);
            }
        });
    }

    /**
     * Stops reading the stream links.
     */
    public void stop() {
        if (this.thread != null) {
            this.thread.quitSafely();
            this.thread = null;
        }
    }

    /**
     * Queries the stream links. Runs on a background thread.
     *
     * @param uri The URI of the stream links to query.
     * @return The stream links as a JSON array, or null if they could not be read.
     */
    @Nullable
    private String query(Uri uri) {
        JSONArray result = new JSONArray();
        try (Cursor cursor = this.ctx.getContentResolver().query(uri, LINKS_PROJECTION, null, null, null)) {
            if (cursor == null) {
                Log.w("RemoteCam stream links are not available"); //NON-NLS
                return null;
            }
            int nameIndex = cursor.getColumnIndexOrThrow(COLUMN_CAMERA_NAME);
            int urlIndex = cursor.getColumnIndexOrThrow(COLUMN_URL);
            while (cursor.moveToNext()) {
                JSONObject camera = new JSONObject();
                camera.put(JSON_NAME, cursor.getString(nameIndex));
                camera.put(JSON_URL, cursor.getString(urlIndex));
                result.put(camera);
            }
        } catch (RuntimeException | JSONException ex) {
            // Thrown e.g. if the permission was revoked or RemoteCam was uninstalled.
            Log.w("Failed to query RemoteCam stream links", ex); //NON-NLS
            return null;
        }
        // The links are capability URLs and are deliberately not logged.
        Log.v("Read %s RemoteCam stream links", result.length()); //NON-NLS
        return result.toString();
    }
}
