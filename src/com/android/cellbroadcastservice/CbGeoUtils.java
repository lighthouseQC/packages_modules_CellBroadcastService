/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cellbroadcastservice;

import android.annotation.NonNull;
import android.telephony.CbGeoUtils.Geometry;
import android.telephony.CbGeoUtils.LatLng;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This utils class is specifically used for geo-targeting of CellBroadcast messages.
 * The coordinates used by this utils class are latitude and longitude, but some algorithms in this
 * class only use them as coordinates on plane, so the calculation will be inaccurate. So don't use
 * this class for anything other then geo-targeting of cellbroadcast messages.
 */
public class CbGeoUtils {
    /**
     * Tolerance for determining if the value is 0. If the absolute value of a value is less than
     * this tolerance, it will be treated as 0.
     */
    public static final double EPS = 1e-7;

    private static final String TAG = "CbGeoUtils";

    /** The TLV tags of WAC, defined in ATIS-0700041 5.2.3 WAC tag coding. */
    public static final int GEO_FENCING_MAXIMUM_WAIT_TIME = 0x01;
    public static final int GEOMETRY_TYPE_POLYGON = 0x02;
    public static final int GEOMETRY_TYPE_CIRCLE = 0x03;

    /** The identifier of geometry in the encoded string. */
    private static final String CIRCLE_SYMBOL = "circle";
    private static final String POLYGON_SYMBOL = "polygon";

    /**
     * The class represents a simple polygon with at least 3 points.
     */
    public static class Polygon implements Geometry {
        /**
         * In order to reduce the loss of precision in floating point calculations, all vertices
         * of the polygon are scaled. Set the value of scale to 1000 can take into account the
         * actual distance accuracy of 1 meter if the EPS is 1e-7 during the calculation.
         */
        private static final double SCALE = 1000.0;

        private final List<LatLng> mVertices;
        private final List<Point> mScaledVertices;
        private final LatLng mOrigin;

        /**
         * Constructs a simple polygon from the given vertices. The adjacent two vertices are
         * connected to form an edge of the polygon. The polygon has at least 3 vertices, and the
         * last vertices and the first vertices must be adjacent.
         *
         * The longitude difference in the vertices should be less than 180 degree.
         */
        public Polygon(@NonNull List<LatLng> vertices) {
            mVertices = vertices;

            // Find the point with smallest longitude as the mOrigin point.
            int idx = 0;
            for (int i = 1; i < vertices.size(); i++) {
                if (vertices.get(i).lng < vertices.get(idx).lng) {
                    idx = i;
                }
            }
            mOrigin = vertices.get(idx);

            mScaledVertices = vertices.stream()
                    .map(latLng -> convertAndScaleLatLng(latLng))
                    .collect(Collectors.toList());
        }

        public List<LatLng> getVertices() {
            return mVertices;
        }

        /**
         * Check if the given point {@code p} is inside the polygon. This method counts the number
         * of times the polygon winds around the point P, A.K.A "winding number". The point is
         * outside only when this "winding number" is 0.
         *
         * If a point is on the edge of the polygon, it is also considered to be inside the polygon.
         */
        @Override
        public boolean contains(LatLng latLng) {
            Point p = convertAndScaleLatLng(latLng);

            int n = mScaledVertices.size();
            int windingNumber = 0;
            for (int i = 0; i < n; i++) {
                Point a = mScaledVertices.get(i);
                Point b = mScaledVertices.get((i + 1) % n);

                // CCW is counterclockwise
                // CCW = ab x ap
                // CCW > 0 -> ap is on the left side of ab
                // CCW == 0 -> ap is on the same line of ab
                // CCW < 0 -> ap is on the right side of ab
                int ccw = sign(crossProduct(b.subtract(a), p.subtract(a)));

                if (ccw == 0) {
                    if (Math.min(a.x, b.x) <= p.x && p.x <= Math.max(a.x, b.x)
                            && Math.min(a.y, b.y) <= p.y && p.y <= Math.max(a.y, b.y)) {
                        return true;
                    }
                } else {
                    if (sign(a.y - p.y) <= 0) {
                        // upward crossing
                        if (ccw > 0 && sign(b.y - p.y) > 0) {
                            ++windingNumber;
                        }
                    } else {
                        // downward crossing
                        if (ccw < 0 && sign(b.y - p.y) <= 0) {
                            --windingNumber;
                        }
                    }
                }
            }
            return windingNumber != 0;
        }

        /**
         * Move the given point {@code latLng} to the coordinate system with {@code mOrigin} as the
         * origin and scale it. {@code mOrigin} is selected from the vertices of a polygon, it has
         * the smallest longitude value among all of the polygon vertices.
         *
         * @param latLng the point need to be converted and scaled.
         * @Return a {@link Point} object.
         */
        private Point convertAndScaleLatLng(LatLng latLng) {
            double x = latLng.lat - mOrigin.lat;
            double y = latLng.lng - mOrigin.lng;

            // If the point is in different hemispheres(western/eastern) than the mOrigin, and the
            // edge between them cross the 180th meridian, then its relative coordinates will be
            // extended.
            // For example, suppose the longitude of the mOrigin is -178, and the longitude of the
            // point to be converted is 175, then the longitude after the conversion is -8.
            // calculation: (-178 - 8) - (-178).
            if (sign(mOrigin.lng) != 0 && sign(mOrigin.lng) != sign(latLng.lng)) {
                double distCross0thMeridian = Math.abs(mOrigin.lng) + Math.abs(latLng.lng);
                if (sign(distCross0thMeridian * 2 - 360) > 0) {
                    y = sign(mOrigin.lng) * (360 - distCross0thMeridian);
                }
            }
            return new Point(x * SCALE, y * SCALE);
        }

        private static double crossProduct(Point a, Point b) {
            return a.x * b.y - a.y * b.x;
        }

        static final class Point {
            public final double x;
            public final double y;

            Point(double x, double y) {
                this.x = x;
                this.y = y;
            }

            public Point subtract(Point p) {
                return new Point(x - p.x, y - p.y);
            }
        }
    }

    /** The class represents a circle. */
    public static class Circle implements Geometry {
        private final LatLng mCenter;
        private final double mRadiusMeter;

        public Circle(LatLng center, double radiusMeter) {
            this.mCenter = center;
            this.mRadiusMeter = radiusMeter;
        }

        public LatLng getCenter() {
            return mCenter;
        }

        public double getRadius() {
            return mRadiusMeter;
        }

        @Override
        public boolean contains(LatLng p) {
            return mCenter.distance(p) <= mRadiusMeter;
        }
    }

    /**
     * Parse the geometries from the encoded string {@code str}. The string must follow the
     * geometry encoding specified by {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     */
    @NonNull
    public static List<Geometry> parseGeometriesFromString(@NonNull String str) {
        List<Geometry> geometries = new ArrayList<>();
        for (String geometryStr : str.split("\\s*;\\s*")) {
            String[] geoParameters = geometryStr.split("\\s*\\|\\s*");
            switch (geoParameters[0]) {
                case CIRCLE_SYMBOL:
                    geometries.add(new Circle(parseLatLngFromString(geoParameters[1]),
                            Double.parseDouble(geoParameters[2])));
                    break;
                case POLYGON_SYMBOL:
                    List<LatLng> vertices = new ArrayList<>(geoParameters.length - 1);
                    for (int i = 1; i < geoParameters.length; i++) {
                        vertices.add(parseLatLngFromString(geoParameters[i]));
                    }
                    geometries.add(new Polygon(vertices));
                    break;
                default:
                    Log.e(TAG, "Invalid geometry format " + geometryStr);
            }
        }
        return geometries;
    }

    /**
     * Encode a list of geometry objects to string. The encoding format is specified by
     * {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     *
     * @param geometries the list of geometry objects need to be encoded.
     * @return the encoded string.
     */
    @NonNull
    public static String encodeGeometriesToString(@NonNull List<Geometry> geometries) {
        return geometries.stream()
                .map(geometry -> encodeGeometryToString(geometry))
                .filter(encodedStr -> !TextUtils.isEmpty(encodedStr))
                .collect(Collectors.joining(";"));
    }


    /**
     * Encode the geometry object to string. The encoding format is specified by
     * {@link android.provider.Telephony.CellBroadcasts#GEOMETRIES}.
     * @param geometry the geometry object need to be encoded.
     * @return the encoded string.
     */
    @NonNull
    private static String encodeGeometryToString(@NonNull Geometry geometry) {
        StringBuilder sb = new StringBuilder();
        if (geometry instanceof Polygon) {
            sb.append(POLYGON_SYMBOL);
            for (LatLng latLng : ((Polygon) geometry).getVertices()) {
                sb.append("|");
                sb.append(latLng.lat);
                sb.append(",");
                sb.append(latLng.lng);
            }
        } else if (geometry instanceof Circle) {
            sb.append(CIRCLE_SYMBOL);
            Circle circle = (Circle) geometry;

            // Center
            sb.append("|");
            sb.append(circle.getCenter().lat);
            sb.append(",");
            sb.append(circle.getCenter().lng);

            // Radius
            sb.append("|");
            sb.append(circle.getRadius());
        } else {
            Log.e(TAG, "Unsupported geometry object " + geometry);
            return null;
        }
        return sb.toString();
    }

    /**
     * Parse {@link LatLng} from {@link String}. Latitude and longitude are separated by ",".
     * Example: "13.56,-55.447".
     *
     * @param str encoded lat/lng string.
     * @Return {@link LatLng} object.
     */
    @NonNull
    public static LatLng parseLatLngFromString(@NonNull String str) {
        String[] latLng = str.split("\\s*,\\s*");
        return new LatLng(Double.parseDouble(latLng[0]), Double.parseDouble(latLng[1]));
    }

    /**
     * @Return the sign of the given value {@code value} with the specified tolerance. Return 1
     * means the sign is positive, -1 means negative, 0 means the value will be treated as 0.
     */
    public static int sign(double value) {
        if (value > EPS) return 1;
        if (value < -EPS) return -1;
        return 0;
    }
}
