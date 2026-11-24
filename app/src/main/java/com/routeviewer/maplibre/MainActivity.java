
package com.routeviewer.maplibre;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private MapView mapView;
    private TextView status;
    private TextView offlineStatus;
    private Button downloadButton;
    private OfflineRegion offlineRegion;
    private boolean downloading = false;

    private static final String STYLE_URL =
        "https://tiles.openfreemap.org/styles/liberty";

    private static final String REGION_ID =
        "orange-athol-test-v02";

    // Small test area only. Massachusetts will be added later.
    private static final double SOUTH = 42.53;
    private static final double NORTH = 42.66;
    private static final double WEST = -72.40;
    private static final double EAST = -72.15;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);

        MapLibre.getInstance(this);

        FrameLayout frame = new FrameLayout(this);

        mapView = new MapView(this);
        frame.addView(mapView);

        status = new TextView(this);
        status.setText("MapLibre Route Viewer v0.2");
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xBB182A36);
        status.setPadding(18, 12, 18, 12);

        FrameLayout.LayoutParams top =
            new FrameLayout.LayoutParams(
                -1, -2, Gravity.TOP);

        frame.addView(status, top);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xDD182A36);

        downloadButton = new Button(this);
        downloadButton.setText(
            "Download Orange/Athol Test Map");

        offlineStatus = new TextView(this);
        offlineStatus.setTextColor(Color.WHITE);
        offlineStatus.setPadding(18, 4, 18, 12);
        offlineStatus.setText("Checking offline maps...");

        controls.addView(downloadButton);
        controls.addView(offlineStatus);

        FrameLayout.LayoutParams bottom =
            new FrameLayout.LayoutParams(
                -1, -2, Gravity.BOTTOM);

        frame.addView(controls, bottom);

        setContentView(frame);

        downloadButton.setOnClickListener(v ->
            startOfflineDownload());

        mapView.onCreate(saved);

        mapView.getMapAsync(map ->
            map.setStyle(
                new Style.Builder().fromUri(STYLE_URL),
                style -> {
                    try {
                        loadRoute(style, map);
                    } catch (Exception e) {
                        status.setText(
                            "Route load error: " +
                            e.getMessage());
                    }
                }
            )
        );

        checkOfflineRegions();
    }

    // =========================================
    // OFFLINE MAP MANAGEMENT
    // =========================================

    private void checkOfflineRegions() {

        OfflineManager manager =
            OfflineManager.getInstance(this);

        manager.listOfflineRegions(
            new OfflineManager.ListOfflineRegionsCallback() {

                @Override
                public void onList(OfflineRegion[] regions) {

                    if (regions != null) {
                        for (OfflineRegion region : regions) {

                            String metadata = new String(
                                region.getMetadata(),
                                StandardCharsets.UTF_8);

                            if (metadata.equals(REGION_ID)) {
                                offlineRegion = region;
                                checkRegionStatus(region);
                                return;
                            }
                        }
                    }

                    offlineStatus.setText(
                        "Test map not downloaded");
                }

                @Override
                public void onError(String error) {
                    offlineStatus.setText(
                        "Offline database error: " + error);
                }
            }
        );
    }

    private void checkRegionStatus(OfflineRegion region) {

        region.getStatus(
            new OfflineRegion.OfflineRegionStatusCallback() {

                @Override
                public void onStatus(OfflineRegionStatus s) {

                    if (s.isComplete()) {
                        offlineStatus.setText(
                            "Offline test map saved and complete");
                        downloadButton.setText(
                            "Offline map saved");
                        downloadButton.setEnabled(false);
                    } else {
                        offlineStatus.setText(
                            "Incomplete map: " +
                            s.getCompletedResourceCount() +
                            " resources saved");
                        downloadButton.setText(
                            "Resume offline download");
                    }
                }

                @Override
                public void onError(String error) {
                    offlineStatus.setText(
                        "Status error: " + error);
                }
            }
        );
    }

    private void startOfflineDownload() {

        if (downloading) return;

        downloading = true;
        downloadButton.setEnabled(false);

        offlineStatus.setText(
            "Preparing offline download...");

        if (offlineRegion != null) {
            activateRegion(offlineRegion);
            return;
        }

        LatLngBounds bounds =
            new LatLngBounds.Builder()
                .include(new LatLng(SOUTH, WEST))
                .include(new LatLng(NORTH, EAST))
                .build();

        OfflineTilePyramidRegionDefinition definition =
            new OfflineTilePyramidRegionDefinition(
                STYLE_URL,
                bounds,
                9.0,
                12.0,
                1.0f,
                false
            );

        byte[] metadata =
            REGION_ID.getBytes(StandardCharsets.UTF_8);

        OfflineManager.getInstance(this)
            .createOfflineRegion(
                definition,
                metadata,
                new OfflineManager.CreateOfflineRegionCallback() {

                    @Override
                    public void onCreate(OfflineRegion region) {
                        offlineRegion = region;
                        activateRegion(region);
                    }

                    @Override
                    public void onError(String error) {
                        downloading = false;
                        downloadButton.setEnabled(true);
                        offlineStatus.setText(
                            "Create error: " + error);
                    }
                }
            );
    }

    private void activateRegion(OfflineRegion region) {

        region.setObserver(
            new OfflineRegion.OfflineRegionObserver() {

                @Override
                public void onStatusChanged(
                    OfflineRegionStatus s) {

                    long tiles = s.getCompletedTileCount();
                    long bytes = s.getCompletedResourceSize();

                    if (s.isComplete()) {

                        region.setDownloadState(
                            OfflineRegion.STATE_INACTIVE);

                        downloading = false;

                        offlineStatus.setText(
                            "Offline map complete: " +
                            tiles + " tiles");

                        downloadButton.setText(
                            "Offline map saved");
                        downloadButton.setEnabled(false);

                    } else if (
                        tiles >= 2900 ||
                        bytes >= 48L * 1024 * 1024) {

                        region.setDownloadState(
                            OfflineRegion.STATE_INACTIVE);

                        downloading = false;

                        offlineStatus.setText(
                            "Stopped near download limit: " +
                            tiles + " tiles");

                        downloadButton.setText(
                            "Download limit reached");
                        downloadButton.setEnabled(false);

                    } else {

                        offlineStatus.setText(
                            "Downloading: " +
                            s.getCompletedResourceCount() +
                            " / " +
                            s.getRequiredResourceCount() +
                            " resources; " +
                            tiles + " tiles");
                    }
                }

                @Override
                public void onError(OfflineRegionError error) {
                    offlineStatus.setText(
                        "Download error: " +
                        error.getMessage());
                }

                @Override
                public void mapboxTileCountLimitExceeded(
                    long limit) {

                    region.setDownloadState(
                        OfflineRegion.STATE_INACTIVE);

                    downloading = false;
                    downloadButton.setEnabled(false);

                    offlineStatus.setText(
                        "SDK tile limit: " + limit);
                }
            }
        );

        region.setDownloadState(
            OfflineRegion.STATE_ACTIVE);
    }

    // =========================================
    // EXISTING ROUTE DISPLAY
    // =========================================

    private void loadRoute(
        Style style,
        MapLibreMap map) throws Exception {

        String json = readAsset("route_demo.json");
        JSONObject route = new JSONObject(json);

        JSONArray stops = route.getJSONArray("stops");
        JSONArray geometry = route.getJSONArray("geometry");

        List<Feature> pointFeatures = new ArrayList<>();
        LatLngBounds.Builder bounds =
            new LatLngBounds.Builder();

        for (int i = 0; i < stops.length(); i++) {

            JSONObject stop = stops.getJSONObject(i);

            double lat = stop.getDouble("lat");
            double lon = stop.getDouble("lon");
            int number = stop.optInt("number", i + 1);

            String icon = "stop_" + number;

            style.addImage(icon, numberIcon(number));

            Feature feature = Feature.fromGeometry(
                Point.fromLngLat(lon, lat));

            feature.addStringProperty("icon", icon);
            feature.addStringProperty(
                "address",
                stop.optString("address", ""));

            pointFeatures.add(feature);
            bounds.include(new LatLng(lat, lon));
        }

        style.addSource(
            new GeoJsonSource(
                "stops-source",
                FeatureCollection.fromFeatures(
                    pointFeatures)));

        SymbolLayer markers =
            new SymbolLayer(
                "numbered-stops",
                "stops-source");

        markers.withProperties(
            PropertyFactory.iconImage("{icon}"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(1f)
        );

        style.addLayer(markers);

        if (geometry.length() >= 2) {

            List<Point> coords = new ArrayList<>();

            for (int i = 0; i < geometry.length(); i++) {
                JSONArray p = geometry.getJSONArray(i);

                coords.add(
                    Point.fromLngLat(
                        p.getDouble(0),
                        p.getDouble(1)));
            }

            style.addSource(
                new GeoJsonSource(
                    "route-source",
                    Feature.fromGeometry(
                        LineString.fromLngLats(coords))));

            LineLayer line =
                new LineLayer(
                    "here-route-line",
                    "route-source");

            line.withProperties(
                PropertyFactory.lineColor("#1769AA"),
                PropertyFactory.lineWidth(4f));

            style.addLayerBelow(
                line, "numbered-stops");
        }

        map.moveCamera(
            org.maplibre.android.camera.CameraUpdateFactory
                .newLatLngBounds(bounds.build(), 90));

        status.setText(
            stops.length() +
            " numbered stops • overlap enabled • demo route");
    }

    private Bitmap numberIcon(int n) {

        int size = 88;

        Bitmap b = Bitmap.createBitmap(
            size, size, Bitmap.Config.ARGB_8888);

        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setColor(0xFF1A6DB3);
        c.drawCircle(44, 44, 39, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setColor(Color.WHITE);
        c.drawCircle(44, 44, 36, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);

        p.setTypeface(
            android.graphics.Typeface.create(
                "sans-serif",
                android.graphics.Typeface.BOLD));

        p.setTextSize(n >= 100 ? 31 : 39);
        c.drawText(String.valueOf(n), 44, 57, p);

        return b;
    }

    private String readAsset(
        String filename) throws Exception {

        try (
            InputStream in = getAssets().open(filename);
            ByteArrayOutputStream out =
                new ByteArrayOutputStream()
        ) {

            byte[] buffer = new byte[4096];
            int n;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            return out.toString("UTF-8");
        }
    }

    // =========================================
    // ANDROID LIFECYCLE
    // =========================================

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        mapView.onStop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        mapView.onSaveInstanceState(state);
    }
}
