package info.varden.hauk.navigation;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import info.varden.hauk.Constants;
import info.varden.hauk.utils.Log;
import info.varden.hauk.utils.TimeUtils;

/**
 * Reads the navigation state of CoMaps through its NavigationContentProvider.
 *
 * <p>CoMaps notifies observers of the live navigation data URI on every location update while
 * navigating, and of both URIs whenever the route changes. This class observes those notifications
 * and keeps an up-to-date {@link NavigationSnapshot}. It never queries CoMaps on its own, so it does
 * not wake up CoMaps when it is not navigating.</p>
 */
public final class ComapsNavigationSource {
    private static final String AUTHORITY_SUFFIX = ".provider.navigation";
    private static final String PERMISSION_SUFFIX = ".permission.READ_NAVIGATION_DATA";
    private static final String PATH_LIVE = "live";
    private static final String PATH_ROUTE = "route";

    private static final String COLUMN_SESSION_STATE = "session_state";
    private static final String COLUMN_TOTAL_TIME_SECONDS = "total_time_seconds";
    private static final String COLUMN_COMPLETION_PERCENT = "completion_percent";
    private static final String COLUMN_ROUTE_REVISION = "route_revision";
    private static final String COLUMN_ROUTE_POINT_COUNT = "route_point_count";
    private static final String COLUMN_LAT = "lat";
    private static final String COLUMN_LON = "lon";

    private static final String[] LIVE_PROJECTION = {
            COLUMN_SESSION_STATE,
            COLUMN_TOTAL_TIME_SECONDS,
            COLUMN_COMPLETION_PERCENT,
            COLUMN_ROUTE_REVISION,
            COLUMN_ROUTE_POINT_COUNT
    };
    private static final String[] ROUTE_PROJECTION = {COLUMN_LAT, COLUMN_LON};

    /**
     * Routing session states (RoutingInfo.RoutingSessionState in CoMaps) during which the user is
     * actively following a route.
     */
    private static final Set<String> ACTIVE_STATES = new HashSet<>(Arrays.asList(
            "OnRoute", "OffRoute", "RouteNeedsRebuild", "RouteRebuilding"
    ));

    /**
     * Initial tolerance in meters used to simplify the route before it is uploaded.
     */
    private static final double ROUTE_SIMPLIFICATION_TOLERANCE = 5.0D;

    /**
     * Maximum length of the encoded route. The simplification tolerance is increased until the
     * encoded route fits. The backend rejects routes longer than 200000 characters.
     */
    private static final int MAX_ENCODED_ROUTE_LENGTH = 100000;

    /**
     * If CoMaps has not sent any update for this long, navigation is considered to have ended,
     * e.g. because CoMaps was killed.
     */
    private static final long MAX_SNAPSHOT_AGE = 180L * TimeUtils.MILLIS_PER_SECOND;

    /**
     * Android application context.
     */
    private final Context ctx;

    private final Uri liveUri;
    private final Uri routeUri;

    /**
     * Background thread on which notifications are received and the provider is queried.
     */
    private HandlerThread thread = null;
    private ContentObserver observer = null;

    /**
     * The most recent navigation state.
     */
    private volatile NavigationSnapshot snapshot = NavigationSnapshot.INACTIVE;

    // The following fields cache the current route and are only accessed from the handler thread.
    private int routeId = 0;
    private int routeRevision = -1;
    private int routePointCount = -1;
    @Nullable
    private String encodedRoute = null;
    private double routeLength = 0.0D;

    private ComapsNavigationSource(Context ctx, String packageName) {
        this.ctx = ctx.getApplicationContext();
        Uri base = Uri.parse("content://" + packageName + AUTHORITY_SUFFIX); //NON-NLS
        this.liveUri = Uri.withAppendedPath(base, PATH_LIVE);
        this.routeUri = Uri.withAppendedPath(base, PATH_ROUTE);
    }

    /**
     * Creates a navigation source for the first installed CoMaps build that Hauk has been granted
     * access to.
     *
     * @param ctx Android application context.
     * @return A navigation source, or null if no CoMaps build is installed or none have granted
     *         access to their navigation data.
     */
    @Nullable
    public static ComapsNavigationSource create(Context ctx) {
        for (String pkg : getInstalledPackages(ctx)) {
            if (ctx.checkSelfPermission(getPermission(pkg)) == PackageManager.PERMISSION_GRANTED) {
                Log.i("Using CoMaps navigation data from %s", pkg); //NON-NLS
                return new ComapsNavigationSource(ctx, pkg);
            }
        }
        Log.w("No CoMaps installation with granted navigation data permission found"); //NON-NLS
        return null;
    }

    /**
     * Returns the package names of all installed CoMaps builds that expose navigation data, in
     * order of preference.
     *
     * @param ctx Android application context.
     */
    @NonNull
    public static List<String> getInstalledPackages(Context ctx) {
        List<String> installed = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : Constants.COMAPS_PACKAGES) {
            if (pm.resolveContentProvider(pkg + AUTHORITY_SUFFIX, 0) != null) installed.add(pkg);
        }
        return installed;
    }

    /**
     * Returns the runtime permission required to read navigation data from the given CoMaps build.
     *
     * @param packageName The package name of the CoMaps build.
     */
    @NonNull
    public static String getPermission(String packageName) {
        return packageName + PERMISSION_SUFFIX;
    }

    /**
     * Starts listening for navigation updates.
     */
    public void start() {
        this.thread = new HandlerThread("ComapsNavigationSource"); //NON-NLS
        this.thread.start();
        this.observer = new ContentObserver(new Handler(this.thread.getLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
        try {
            this.ctx.getContentResolver().registerContentObserver(this.liveUri, false, this.observer);
        } catch (SecurityException ex) {
            Log.e("Not allowed to observe CoMaps navigation data", ex); //NON-NLS
        }
    }

    /**
     * Stops listening for navigation updates.
     */
    public void stop() {
        if (this.observer != null) {
            this.ctx.getContentResolver().unregisterContentObserver(this.observer);
            this.observer = null;
        }
        if (this.thread != null) {
            this.thread.quitSafely();
            this.thread = null;
        }
        this.snapshot = NavigationSnapshot.INACTIVE;
    }

    /**
     * Returns the most recent navigation state. Safe to call from any thread.
     */
    @NonNull
    public NavigationSnapshot getSnapshot() {
        NavigationSnapshot current = this.snapshot;
        if (current.isActive() && SystemClock.elapsedRealtime() - current.getTimestamp() > MAX_SNAPSHOT_AGE) {
            return NavigationSnapshot.INACTIVE;
        }
        return current;
    }

    /**
     * Queries the live navigation data, and the route geometry if it has changed, and updates the
     * snapshot. Runs on the handler thread.
     */
    private void refresh() {
        try (Cursor cursor = this.ctx.getContentResolver().query(this.liveUri, LIVE_PROJECTION, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                setInactive();
                return;
            }

            String state = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SESSION_STATE));
            int totalTime = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_TOTAL_TIME_SECONDS));
            double completion = cursor.getDouble(cursor.getColumnIndexOrThrow(COLUMN_COMPLETION_PERCENT));
            int revision = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ROUTE_REVISION));
            int pointCount = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ROUTE_POINT_COUNT));

            // CoMaps keeps returning the last routing info after navigation has ended, but clears
            // the route geometry, so the point count is checked as well.
            if (!ACTIVE_STATES.contains(state) || pointCount < 2) {
                setInactive();
                return;
            }

            if (revision != this.routeRevision || pointCount != this.routePointCount) {
                loadRoute(revision, pointCount);
            }
            if (this.encodedRoute == null) {
                setInactive();
                return;
            }

            long eta = System.currentTimeMillis() / TimeUtils.MILLIS_PER_SECOND + totalTime;
            double remaining = Math.max(0.0D, this.routeLength * (1.0D - completion / 100.0D));
            this.snapshot = new NavigationSnapshot(this.routeId, this.encodedRoute, eta, remaining, SystemClock.elapsedRealtime());
        } catch (RuntimeException ex) {
            // Thrown e.g. if the permission was revoked or CoMaps was uninstalled.
            Log.w("Failed to query CoMaps navigation data", ex); //NON-NLS
            setInactive();
        }
    }

    /**
     * Loads, simplifies and encodes the route geometry. Runs on the handler thread.
     */
    private void loadRoute(int revision, int pointCount) {
        List<double[]> points = new ArrayList<>(pointCount);
        try (Cursor cursor = this.ctx.getContentResolver().query(this.routeUri, ROUTE_PROJECTION, null, null, null)) {
            if (cursor != null) {
                int latIndex = cursor.getColumnIndexOrThrow(COLUMN_LAT);
                int lonIndex = cursor.getColumnIndexOrThrow(COLUMN_LON);
                while (cursor.moveToNext()) {
                    points.add(new double[] {cursor.getDouble(latIndex), cursor.getDouble(lonIndex)});
                }
            }
        }

        this.routeRevision = revision;
        this.routePointCount = pointCount;
        if (points.size() < 2) {
            this.encodedRoute = null;
            return;
        }

        String encoded;
        double tolerance = ROUTE_SIMPLIFICATION_TOLERANCE;
        do {
            encoded = Polyline.encode(Polyline.simplify(points, tolerance));
            tolerance *= 2.0D;
        } while (encoded.length() > MAX_ENCODED_ROUTE_LENGTH);

        this.routeId++;
        this.encodedRoute = encoded;
        this.routeLength = Polyline.length(points);
        Log.i("Loaded CoMaps route revision %s: %s points, %s m, %s chars encoded", revision, points.size(), Math.round(this.routeLength), encoded.length()); //NON-NLS
    }

    /**
     * Marks navigation as inactive and clears the cached route. Runs on the handler thread.
     */
    private void setInactive() {
        this.routeRevision = -1;
        this.routePointCount = -1;
        this.encodedRoute = null;
        this.snapshot = NavigationSnapshot.INACTIVE;
    }
}
