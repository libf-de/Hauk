package info.varden.hauk.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PolylineTest {
    @Test
    public void encodeMatchesReferenceExample() {
        // Example from the Encoded Polyline Algorithm Format documentation.
        List<double[]> points = Arrays.asList(
                new double[] {38.5, -120.2},
                new double[] {40.7, -120.95},
                new double[] {43.252, -126.453}
        );
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(points));
    }

    @Test
    public void encodeEmpty() {
        assertEquals("", Polyline.encode(new ArrayList<>()));
    }

    @Test
    public void simplifyRemovesCollinearPoints() {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i <= 100; i++) points.add(new double[] {52.0 + i * 0.0001, 13.0});
        List<double[]> simplified = Polyline.simplify(points, 1.0);
        assertEquals(2, simplified.size());
        assertSame(points.get(0), simplified.get(0));
        assertSame(points.get(100), simplified.get(1));
    }

    @Test
    public void simplifyKeepsCorners() {
        List<double[]> points = Arrays.asList(
                new double[] {52.0, 13.0},
                new double[] {52.0005, 13.0},
                new double[] {52.001, 13.0},
                new double[] {52.001, 13.001},
                new double[] {52.001, 13.002}
        );
        List<double[]> simplified = Polyline.simplify(points, 5.0);
        assertEquals(3, simplified.size());
        assertSame(points.get(2), simplified.get(1));
    }

    @Test
    public void lengthOfOneDegreeLatitude() {
        List<double[]> points = Arrays.asList(new double[] {0.0, 0.0}, new double[] {1.0, 0.0});
        assertEquals(111195.0, Polyline.length(points), 1.0);
    }
}
