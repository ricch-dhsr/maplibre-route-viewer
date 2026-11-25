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
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

/** Offline test v0.5: high-detail Athol pilot, no automatic route camera movement. */
public class MainActivity extends Activity {
    private MapView mapView;
    private TextView status, offlineStatus;
    private Button downloadButton, showRouteButton;
    private Spinner regionPicker;
    private final java.util.HashMap<String, OfflineRegion> savedRegions = new java.util.HashMap<>();
    private int activeRegionIndex = -1;
    private OfflineRegion offlineRegion;
    private boolean downloading;
    private MapLibreMap readyMap;
    private Style readyStyle;
    private boolean demoRouteShown;

    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private static final String[] REGION_NAMES = {
        "Athol high-detail test (z9-16)", "Western MA pilot", "Central MA pilot",
        "Eastern MA pilot", "Cape & Islands pilot"
    };
    private static final String[] REGION_IDS = {
        "athol-detail-test-v05", "western-ma-pilot-v04", "central-ma-pilot-v04",
        "eastern-ma-pilot-v04", "cape-islands-pilot-v04"
    };
    // Pilot rectangles, NOT verified complete Massachusetts coverage.
    // Coordinates: south, north, west, east.
    private static final double[][] AREAS = {
        {42.58, 42.62, -72.30, -72.25},
        {42.00, 42.80, -73.50, -72.45},
        {42.00, 42.80, -72.45, -71.55},
        {42.00, 42.80, -71.55, -70.55},
        {41.20, 42.05, -71.30, -69.90}
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        MapLibre.getInstance(this);

        // Limit this application's concurrent HTTP requests and identify the client.
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(4);
        dispatcher.setMaxRequestsPerHost(4);
        OkHttpClient mapClient = new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "RichMapLibreRouteViewer/0.5 (Android)")
                .build()))
            .build();
        HttpRequestUtil.setOkHttpClient(mapClient);
        OfflineManager.getInstance(this).setOfflineMapboxTileCountLimit(100000L);

        FrameLayout frame = new FrameLayout(this);
        mapView = new MapView(this);
        frame.addView(mapView);
        status = new TextView(this);
        status.setText("MapLibre Route Viewer v0.5 • clean offline test");
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xBB182A36);
        status.setPadding(18, 12, 18, 12);
        frame.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xDD182A36);
        regionPicker = new Spinner(this);
        ArrayAdapter<String> regionAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, REGION_NAMES);
        regionPicker.setAdapter(regionAdapter);
        controls.addView(regionPicker);
        showRouteButton = new Button(this);
        showRouteButton.setText("Show demo route (use AFTER disconnecting)");
        showRouteButton.setEnabled(false);
        controls.addView(showRouteButton);
        showRouteButton.setOnClickListener(v -> {
            if (readyMap == null || readyStyle == null || demoRouteShown) return;
            try {
                loadRoute(readyStyle, readyMap);
                demoRouteShown = true;
                showRouteButton.setEnabled(false);
            } catch (Exception e) {
                status.setText("Route load error: " + e.getMessage());
            }
        });
        downloadButton = new Button(this);
        downloadButton.setText("Download selected region");
        offlineStatus = new TextView(this);
        offlineStatus.setTextColor(Color.WHITE);
        offlineStatus.setPadding(18, 4, 18, 12);
        offlineStatus.setText("Checking offline maps...");
        controls.addView(downloadButton);
        controls.addView(offlineStatus);
        regionPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                    android.view.View view, int position, long id) {
                if (!downloading) refreshSelectedRegion();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(frame);
        downloadButton.setOnClickListener(v -> startOfflineDownload());

        mapView.onCreate(saved);
        mapView.getMapAsync(map -> {
            // Keep the camera well away from Massachusetts until the user
            // explicitly chooses to view the route AFTER disabling internet.
            map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
                .newLatLngZoom(new LatLng(40.6, -67.5), 7.0));
            map.setStyle(new Style.Builder().fromUri(STYLE_URL), style -> {
                readyMap = map;
                readyStyle = style;
                showRouteButton.setEnabled(true);
                status.setText("Map ready away from test area • download first, then go offline");
            });
        });
        checkOfflineRegions();
    }

    private String formatSize(long bytes) {
        return String.format(Locale.US, "%.2f MB", bytes / 1048576.0);
    }

    private void checkOfflineRegions() {
        OfflineManager.getInstance(this).listOfflineRegions(
            new OfflineManager.ListOfflineRegionsCallback() {
                @Override public void onList(OfflineRegion[] regions) {
                    savedRegions.clear();
                    if (regions != null) for (OfflineRegion region : regions) {
                        String metadata = new String(region.getMetadata(), StandardCharsets.UTF_8);
                        savedRegions.put(metadata, region);
                    }
                    refreshSelectedRegion();
                }
                @Override public void onError(String error) {
                    offlineStatus.setText("Offline database error: " + error);
                }
            });
    }

    private void refreshSelectedRegion() {
        int index = regionPicker.getSelectedItemPosition();
        if (index < 0 || index >= REGION_IDS.length) return;
        OfflineRegion region = savedRegions.get(REGION_IDS[index]);
        downloadButton.setEnabled(true);
        downloadButton.setText(region == null ? "Download selected region" : "Check / resume region");
        if (region == null) {
            offlineStatus.setText(REGION_NAMES[index] + ": not downloaded (zoom " + (index == 0 ? "9–16" : "9–12") + ")");
            return;
        }
        region.getStatus(new OfflineRegion.OfflineRegionStatusCallback() {
            @Override public void onStatus(OfflineRegionStatus st) {
                if (index != regionPicker.getSelectedItemPosition() || downloading) return;
                offlineStatus.setText(REGION_NAMES[index] + ": " +
                    (st.isComplete() ? "COMPLETE" : "incomplete") + " • " +
                    st.getCompletedTileCount() + " tiles • " +
                    formatSize(st.getCompletedResourceSize()));
                downloadButton.setText(st.isComplete() ? "Region already saved" : "Resume selected region");
                downloadButton.setEnabled(!st.isComplete());
            }
            @Override public void onError(String error) {
                offlineStatus.setText("Status error: " + error);
            }
        });
    }

    private void startOfflineDownload() {
        if (downloading) return;
        int index = regionPicker.getSelectedItemPosition();
        if (index < 0 || index >= REGION_IDS.length) return;
        downloading = true;
        activeRegionIndex = index;
        regionPicker.setEnabled(false);
        downloadButton.setEnabled(false);
        offlineStatus.setText("Preparing " + REGION_NAMES[index] + "...");
        OfflineRegion existing = savedRegions.get(REGION_IDS[index]);
        if (existing != null) {
            existing.getStatus(new OfflineRegion.OfflineRegionStatusCallback() {
                @Override public void onStatus(OfflineRegionStatus st) {
                    if (st.isComplete()) {
                        downloading = false;
                        activeRegionIndex = -1;
                        regionPicker.setEnabled(true);
                        refreshSelectedRegion();
                    } else {
                        activateRegion(existing, index);
                    }
                }
                @Override public void onError(String error) {
                    finishWithError("Status error: " + error);
                }
            });
            return;
        }

        double[] a = AREAS[index];
        LatLngBounds bounds = new LatLngBounds.Builder()
            .include(new LatLng(a[0], a[2]))
            .include(new LatLng(a[1], a[3])).build();
        OfflineTilePyramidRegionDefinition definition =
            new OfflineTilePyramidRegionDefinition(STYLE_URL, bounds,
                9.0, index == 0 ? 16.0 : 12.0, 1.0f, false);
        OfflineManager.getInstance(this).createOfflineRegion(definition,
            REGION_IDS[index].getBytes(StandardCharsets.UTF_8),
            new OfflineManager.CreateOfflineRegionCallback() {
                @Override public void onCreate(OfflineRegion region) {
                    savedRegions.put(REGION_IDS[index], region);
                    activateRegion(region, index);
                }
                @Override public void onError(String error) {
                    finishWithError("Create error: " + error);
                }
            });
    }

    private void finishWithError(String message) {
        downloading = false;
        activeRegionIndex = -1;
        regionPicker.setEnabled(true);
        downloadButton.setEnabled(true);
        offlineStatus.setText(message);
    }

    private void activateRegion(OfflineRegion region, int index) {
        region.setObserver(new OfflineRegion.OfflineRegionObserver() {
            @Override public void onStatusChanged(OfflineRegionStatus st) {
                if (activeRegionIndex != index) return;
                long tiles = st.getCompletedTileCount();
                long bytes = st.getCompletedResourceSize();
                if (st.isComplete()) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                    downloading = false;
                    activeRegionIndex = -1;
                    regionPicker.setEnabled(true);
                    offlineStatus.setText(REGION_NAMES[index] + " COMPLETE: " +
                        tiles + " tiles • " + formatSize(bytes));
                    downloadButton.setText("Region already saved");
                    downloadButton.setEnabled(false);
                } else if (tiles >= 2900 || bytes >= 48L * 1024 * 1024) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                    finishWithError("Stopped near per-download limit: " +
                        tiles + " tiles • " + formatSize(bytes));
                    downloadButton.setEnabled(false);
                } else {
                    offlineStatus.setText(REGION_NAMES[index] + ": " +
                        st.getCompletedResourceCount() + " / " +
                        st.getRequiredResourceCount() + " resources • " +
                        tiles + " tiles • " + formatSize(bytes));
                }
            }
            @Override public void onError(OfflineRegionError error) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                finishWithError("Download error: " + error.getMessage());
            }
            @Override public void mapboxTileCountLimitExceeded(long limit) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                finishWithError("SDK tile limit: " + limit);
            }
        });
        region.setDownloadState(OfflineRegion.STATE_ACTIVE);
    }

    // Proven Build #10 route rendering retained without behavior changes.
    private void loadRoute(Style style, MapLibreMap map) throws Exception {
        JSONObject route = new JSONObject(readAsset("route_demo.json"));
        JSONArray stops = route.getJSONArray("stops");
        JSONArray geometry = route.getJSONArray("geometry");
        List<Feature> pointFeatures = new ArrayList<>();
        LatLngBounds.Builder bounds = new LatLngBounds.Builder();
        for (int i = 0; i < stops.length(); i++) {
            JSONObject stop = stops.getJSONObject(i);
            double lat = stop.getDouble("lat"), lon = stop.getDouble("lon");
            int number = stop.optInt("number", i + 1);
            String icon = "stop_" + number;
            style.addImage(icon, numberIcon(number));
            Feature feature = Feature.fromGeometry(Point.fromLngLat(lon, lat));
            feature.addStringProperty("icon", icon);
            feature.addStringProperty("address", stop.optString("address", ""));
            pointFeatures.add(feature);
            bounds.include(new LatLng(lat, lon));
        }
        style.addSource(new GeoJsonSource("stops-source", FeatureCollection.fromFeatures(pointFeatures)));
        SymbolLayer markers = new SymbolLayer("numbered-stops", "stops-source");
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
                coords.add(Point.fromLngLat(p.getDouble(0), p.getDouble(1)));
            }
            style.addSource(new GeoJsonSource("route-source",
                Feature.fromGeometry(LineString.fromLngLats(coords))));
            LineLayer line = new LineLayer("here-route-line", "route-source");
            line.withProperties(PropertyFactory.lineColor("#1769AA"), PropertyFactory.lineWidth(4f));
            style.addLayerBelow(line, "numbered-stops");
        }
        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
            .newLatLngBounds(bounds.build(), 90));
        status.setText(stops.length() + " numbered stops • overlap enabled • demo route");
    }

    private Bitmap numberIcon(int n) {
        Bitmap b = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF1A6DB3); c.drawCircle(44, 44, 39, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6);
        p.setColor(Color.WHITE); c.drawCircle(44, 44, 36, p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        p.setTextSize(n >= 100 ? 31 : 39);
        c.drawText(String.valueOf(n), 44, 57, p);
        return b;
    }

    private String readAsset(String filename) throws Exception {
        try (InputStream in = getAssets().open(filename);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }

    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override protected void onDestroy() { mapView.onDestroy(); super.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); mapView.onSaveInstanceState(state);
    }
}
