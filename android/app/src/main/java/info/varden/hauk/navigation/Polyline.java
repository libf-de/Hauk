package info.varden.hauk.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Utility functions for route geometry: length calculation, simplification and encoding using the
 * Encoded Polyline Algorithm Format.
 *
 * @see <a href="https://developers.google.com/maps/documentation/utilities/polylinealgorithm">Encoded Polyline Algorithm Format</a>
 */
public final class Polyline {
    /**
     * Mean radius of the earth in meters.
     */
    private static final double EARTH_RADIUS = 6371000.0D;

    /**
     * Coordinate precision of the encoded polyline (five decimal places, ~1 m).
     */
    private static final double PRECISION = 1.0E5D;

    private Polyline() {
    }

    /**
     * Calculates the length of a path along the surface of the earth.
     *
     * @param points A list of {latitude, longitude} pairs.
     * @return The length of the path in meters.
     */
    public static double length(List<double[]> points) {
        double length = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            length += distance(points.get(i - 1), points.get(i));
        }
        return length;
    }

    /**
     * Simplifies a path using the Ramer-Douglas-Peucker algorithm. The first and last points are
     * always retained.
     *
     * @param points    A list of {latitude, longitude} pairs.
     * @param tolerance The maximum distance in meters a removed point may deviate from the
     *                  simplified path.
     * @return A new list containing a subset of the given points.
     */
    public static List<double[]> simplify(List<double[]> points, double tolerance) {
        int count = points.size();
        if (count < 3) return new ArrayList<>(points);

        // Project the points onto a plane, in meters, using an equirectangular projection centered
        // on the first point. This is accurate enough for the small deviations considered here.
        double cosLat = Math.cos(Math.toRadians(points.get(0)[0]));
        double[] x = new double[count];
        double[] y = new double[count];
        for (int i = 0; i < count; i++) {
            x[i] = Math.toRadians(points.get(i)[1]) * cosLat * EARTH_RADIUS;
            y[i] = Math.toRadians(points.get(i)[0]) * EARTH_RADIUS;
        }

        // Iterative implementation to avoid stack overflows on routes with many points.
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[count - 1] = true;
        Deque<int[]> segments = new ArrayDeque<>();
        segments.push(new int[] {0, count - 1});
        while (!segments.isEmpty()) {
            int[] segment = segments.pop();
            int first = segment[0];
            int last = segment[1];
            double maxDistance = 0.0D;
            int index = -1;
            for (int i = first + 1; i < last; i++) {
                double distance = segmentDistance(x[i], y[i], x[first], y[first], x[last], y[last]);
                if (distance > maxDistance) {
                    maxDistance = distance;
                    index = i;
                }
            }
            if (index != -1 && maxDistance > tolerance) {
                keep[index] = true;
                segments.push(new int[] {first, index});
                segments.push(new int[] {index, last});
            }
        }

        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (keep[i]) result.add(points.get(i));
        }
        return result;
    }

    /**
     * Encodes a path using the Encoded Polyline Algorithm Format with a precision of 1e5.
     *
     * @param points A list of {latitude, longitude} pairs.
     * @return The encoded polyline string.
     */
    public static String encode(List<double[]> points) {
        StringBuilder sb = new StringBuilder();
        long prevLat = 0L;
        long prevLon = 0L;
        for (double[] point : points) {
            long lat = Math.round(point[0] * PRECISION);
            long lon = Math.round(point[1] * PRECISION);
            encodeValue(lat - prevLat, sb);
            encodeValue(lon - prevLon, sb);
            prevLat = lat;
            prevLon = lon;
        }
        return sb.toString();
    }

    /**
     * Encodes a single signed value and appends it to the given string builder.
     */
    private static void encodeValue(long value, StringBuilder sb) {
        long v = value < 0L ? ~(value << 1) : value << 1;
        while (v >= 0x20L) {
            sb.append((char) ((0x20L | (v & 0x1FL)) + 63L));
            v >>= 5;
        }
        sb.append((char) (v + 63L));
    }

    /**
     * Calculates the great-circle distance between two points using the haversine formula.
     *
     * @return The distance in meters.
     */
    private static double distance(double[] from, double[] to) {
        double dLat = Math.toRadians(to[0] - from[0]);
        double dLon = Math.toRadians(to[1] - from[1]);
        double a = Math.sin(dLat / 2.0D) * Math.sin(dLat / 2.0D)
                + Math.cos(Math.toRadians(from[0])) * Math.cos(Math.toRadians(to[0]))
                * Math.sin(dLon / 2.0D) * Math.sin(dLon / 2.0D);
        return 2.0D * EARTH_RADIUS * Math.asin(Math.min(1.0D, Math.sqrt(a)));
    }

    /**
     * Calculates the distance between point P and the line segment AB in a plane.
     */
    private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq == 0.0D ? 0.0D : ((px - ax) * dx + (py - ay) * dy) / lengthSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double cx = ax + t * dx - px;
        double cy = ay + t * dy - py;
        return Math.sqrt(cx * cx + cy * cy);
    }
}
