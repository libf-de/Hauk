package info.varden.hauk.navigation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An immutable snapshot of the navigation state of an external navigation app.
 */
public final class NavigationSnapshot {
    /**
     * A snapshot representing that no navigation is currently active.
     */
    public static final NavigationSnapshot INACTIVE = new NavigationSnapshot(0, null, 0L, 0.0D, 0L);

    /**
     * A locally generated ID of the route. Changes whenever the route geometry changes, e.g. when
     * the route is recalculated.
     */
    private final int routeId;

    /**
     * The simplified route geometry in the Encoded Polyline Algorithm Format, or null if inactive.
     */
    @Nullable
    private final String encodedRoute;

    /**
     * The estimated time of arrival as a UNIX timestamp in seconds.
     */
    private final long eta;

    /**
     * The estimated remaining distance to the destination in meters.
     */
    private final double remainingDistance;

    /**
     * The time this snapshot was taken, in milliseconds of {@link android.os.SystemClock#elapsedRealtime()}.
     */
    private final long timestamp;

    NavigationSnapshot(int routeId, @Nullable String encodedRoute, long eta, double remainingDistance, long timestamp) {
        this.routeId = routeId;
        this.encodedRoute = encodedRoute;
        this.eta = eta;
        this.remainingDistance = remainingDistance;
        this.timestamp = timestamp;
    }

    public boolean isActive() {
        return this.encodedRoute != null;
    }

    public int getRouteId() {
        return this.routeId;
    }

    @Nullable
    public String getEncodedRoute() {
        return this.encodedRoute;
    }

    public long getETA() {
        return this.eta;
    }

    public double getRemainingDistance() {
        return this.remainingDistance;
    }

    long getTimestamp() {
        return this.timestamp;
    }

    @NonNull
    @Override
    public String toString() {
        return "NavigationSnapshot{routeId=" + this.routeId
                + ",routeLength=" + (this.encodedRoute == null ? -1 : this.encodedRoute.length())
                + ",eta=" + this.eta
                + ",remainingDistance=" + this.remainingDistance
                + "}";
    }
}
