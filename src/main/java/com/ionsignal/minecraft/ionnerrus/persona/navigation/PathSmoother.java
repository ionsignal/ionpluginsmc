package com.ionsignal.minecraft.ionnerrus.persona.navigation;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PathSmoother {

    private PathSmoother() {
    }

    /**
     * Generates a smooth path using a Catmull-Rom spline.
     *
     * @param waypoints The key points the path must pass through.
     * @param pointsPerSegment The number of interpolated points to generate between each waypoint.
     * @return A list of locations representing the smoothed path.
     */
    public static List<Location> generateSpline(List<Location> waypoints, int pointsPerSegment) {
        if (waypoints == null || waypoints.size() < 2) {
            return waypoints; // Not enough points to form a path
        }

        List<Location> smoothedPath = new ArrayList<>();
        // The spline needs 4 points to calculate the curve (p0, p1, p2, p3) to go from p1 to p2.
        // We "pad" the list by duplicating the start and end points to handle the edges.
        List<Location> controlPoints = new ArrayList<>(waypoints);
        controlPoints.add(0, waypoints.get(0));
        controlPoints.add(waypoints.get(waypoints.size() - 1));

        for (int i = 0; i < controlPoints.size() - 3; i++) {
            Vector p0 = controlPoints.get(i).toVector();
            Vector p1 = controlPoints.get(i + 1).toVector();
            Vector p2 = controlPoints.get(i + 2).toVector();
            Vector p3 = controlPoints.get(i + 3).toVector();

            for (int j = 0; j < pointsPerSegment; j++) {
                float t = (float) j / pointsPerSegment;
                Vector interpolatedPoint = catmullRom(p0, p1, p2, p3, t);
                smoothedPath.add(interpolatedPoint.toLocation(waypoints.get(0).getWorld()));
            }
        }

        // Ensure the final destination is exactly correct
        smoothedPath.add(waypoints.get(waypoints.size() - 1));
        return Collections.unmodifiableList(smoothedPath);
    }

    /**
     * Calculates a point on a Catmull-Rom spline.
     *
     * @param p0 The first control point.
     * @param p1 The second control point (start of the curve segment).
     * @param p2 The third control point (end of the curve segment).
     * @param p3 The fourth control point.
     * @param t  The interpolation factor (0.0 to 1.0).
     * @return The interpolated vector.
     */
    private static Vector catmullRom(Vector p0, Vector p1, Vector p2, Vector p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;

        Vector a = p1.clone().multiply(2);
        Vector b = p2.clone().subtract(p0).multiply(t);
        Vector c = p0.clone().multiply(2).subtract(p1.clone().multiply(5)).add(p2.clone().multiply(4)).subtract(p3).multiply(t2);
        Vector d = p0.clone().multiply(-1).add(p1.clone().multiply(3)).subtract(p2.clone().multiply(3)).add(p3).multiply(t3);

        return a.add(b).add(c).add(d).multiply(0.5);
    }
}