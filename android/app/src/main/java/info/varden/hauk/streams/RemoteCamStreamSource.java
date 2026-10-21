package info.varden.hauk.streams;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import info.varden.hauk.Constants;
import info.varden.hauk.utils.Log;

/**
 * Reads the public live stream links of the cameras of the RemoteCam account the RemoteCam app on
 * this phone is logged in to, through its StreamLinksProvider.
 *
 * <p>The links are not expected to change while sharing, so they are only read once, when
 * {@link #load()} is called.</p>
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
     * Keys of the camera objects in the JSON array sent to the backend.
     */
    private static final String JSON_NAME = "name";
    private static final String JSON_URL = "url";

    /**
     * Android application context.
     */
    private final Context ctx;

    private final Uri linksUri;

    /**
     * The stream links as a JSON array, or null if they have not been loaded (yet).
     */
    @Nullable
    private volatile String streams = null;

    private RemoteCamStreamSource(Context ctx, String packageName) {
        this.ctx = ctx.getApplicationContext();
        this.linksUri = Uri.parse("content://" + packageName + AUTHORITY_SUFFIX).buildUpon() //NON-NLS
                .appendPath(PATH_LINKS)
                .appendQueryParameter(PARAM_REFRESH, "1")
                .build();
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
     * Loads the stream links in the background. RemoteCam fetches them from its server first, which
     * can take a few seconds.
     */
    public void load() {
        new Thread(this::query, "RemoteCamStreamSource").start(); //NON-NLS
    }

    /**
     * Returns the stream links as a JSON array of objects with the camera name and the URL of its
     * stream page, or null if they have not been loaded (yet). Safe to call from any thread.
     */
    @Nullable
    public String getStreams() {
        return this.streams;
    }

    /**
     * Queries the stream links. Runs on a background thread.
     */
    private void query() {
        JSONArray result = new JSONArray();
        try (Cursor cursor = this.ctx.getContentResolver().query(this.linksUri, LINKS_PROJECTION, null, null, null)) {
            if (cursor == null) {
                Log.w("RemoteCam stream links are not available"); //NON-NLS
                return;
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
            return;
        }
        // The links are capability URLs and are deliberately not logged.
        Log.i("Loaded %s RemoteCam stream links", result.length()); //NON-NLS
        this.streams = result.toString();
    }
}
