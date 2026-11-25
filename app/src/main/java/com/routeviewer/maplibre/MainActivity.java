package com.routeviewer.maplibre;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.geometry.LatLng;

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
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

/** v0.9: adds optional house-number labels to existing offline vector tiles. */
public class MainActivity extends Activity {
    private MapView mapView;
    private TextView status, offlineStatus;
    private Button downloadButton, showRouteButton, showRegionButton, diagnosticButton;
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
        "Athol high-detail test (z9-16)", "Greenfield detail test (z9-16)", "Western MA pilot", "Central MA pilot",
        "Eastern MA pilot", "Cape & Islands pilot"
    };
    private static final String[] REGION_IDS = {
        "athol-detail-test-v05", "greenfield-detail-test-v07", "western-ma-pilot-v04", "central-ma-pilot-v04",
        "eastern-ma-pilot-v04", "cape-islands-pilot-v04"
    };
    // Pilot rectangles, NOT verified complete Massachusetts coverage.
    // Coordinates: south, north, west, east.
    private static final double[][] AREAS = {
        {42.58, 42.62, -72.30, -72.25},
        {42.56, 42.60, -72.62, -72.57},
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
                .header("User-Agent", "RichMapLibreRouteViewer/1.0 (Android)")
                .build()))
            .build();
        HttpRequestUtil.setOkHttpClient(mapClient);
        OfflineManager.getInstance(this).setOfflineMapboxTileCountLimit(100000L);

        FrameLayout frame = new FrameLayout(this);
        mapView = new MapView(this);
        frame.addView(mapView);
        status = new TextView(this);
        status.setText("MapLibre Route Viewer v1.0 • house-number diagnostics");
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
        showRegionButton = new Button(this);
        showRegionButton.setText("Show selected region (AFTER disconnecting)");
        showRegionButton.setEnabled(false);
        controls.addView(showRegionButton);
        diagnosticButton = new Button(this);
        diagnosticButton.setText("HOUSE NUMBER DIAGNOSTICS (CURRENT VIEW)");
        diagnosticButton.setEnabled(false);
        controls.addView(diagnosticButton);
        diagnosticButton.setOnClickListener(v -> runHouseNumberDiagnostics());
        showRegionButton.setOnClickListener(v -> {
            if (readyMap == null || downloading) return;
            int i = regionPicker.getSelectedItemPosition();
            if (i < 0 || i >= AREAS.length) return;
            double[] a = AREAS[i];
            readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
                .newLatLngZoom(new LatLng((a[0]+a[1])/2, (a[2]+a[3])/2), 15.0));
        });
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
            // Keep the map flat even if the user accidentally performs a tilt gesture.
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.setMaxPitchPreference(0.0);
            map.setStyle(new Style.Builder().fromUri(STYLE_URL), style -> {
                // Replace extrusion rendering with a flat footprint layer using
                // the same source, source-layer, and feature filter.
                // Hiding extrusions alone removed buildings in Greenfield.
                for (Layer layer : style.getLayers()) {
                    if (layer instanceof FillExtrusionLayer) {
                        FillExtrusionLayer extrusion = (FillExtrusionLayer) layer;
                        FillLayer flat = new FillLayer(
                            "flat-" + extrusion.getId(), extrusion.getSourceId());
                        if (extrusion.getSourceLayer() != null) {
                            flat.setSourceLayer(extrusion.getSourceLayer());
                        }
                        if (extrusion.getFilter() != null) {
                            flat.setFilter(extrusion.getFilter());
                        }
                        flat.setProperties(
                            PropertyFactory.fillColor("#D6D5D2"),
                            PropertyFactory.fillOpacity(0.9f)
                        );
                        style.addLayerBelow(flat, extrusion.getId());
                        extrusion.setProperties(PropertyFactory.visibility("none"));
                    }
                }
                // OpenMapTiles address labels share the vector source used by
                // Liberty's building layer. Reuse that source; no new tiles,
                // offline regions, or network requests are needed.
                String addressSource = null;
                for (Layer layer : style.getLayers()) {
                    if (layer instanceof FillExtrusionLayer) {
                        addressSource = ((FillExtrusionLayer) layer).getSourceId();
                        break;
                    }
                }
                if (addressSource != null) {
                    SymbolLayer houseNumbers = new SymbolLayer(
                        "delivery-house-numbers", addressSource);
                    houseNumbers.setSourceLayer("housenumber");
                    houseNumbers.setMinZoom(15.0f);
                    houseNumbers.withProperties(
                        PropertyFactory.textField("{housenumber}"),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor("#333333"),
                        PropertyFactory.textHaloColor("#FFFFFF"),
                        PropertyFactory.textHaloWidth(1.2f),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textIgnorePlacement(false)
                    );
                    style.addLayer(houseNumbers);
                }
                readyMap = map;
                readyStyle = style;
                showRouteButton.setEnabled(true);
                showRegionButton.setEnabled(true);
                diagnosticButton.setEnabled(true);
                status.setText("Map ready away from test area • download first, then go offline");
            });
        });
        checkOfflineRegions();
    }

    // Source queries inspect loaded vector tiles, including features that are
    // not drawn by a style layer. Pan/zoom and wait for tiles before running.
    private void runHouseNumberDiagnostics() {
        if (readyMap == null || readyStyle == null) return;
        StringBuilder report = new StringBuilder();
        int houseTotal = 0;
        int buildingTotal = 0;
        int addressProperties = 0;
        try {
            LatLng center = readyMap.getCameraPosition().target;
            report.append("MAPLIBRE HOUSE NUMBER DIAGNOSTICS v1.0\\n");
            report.append("Center: ").append(center.getLatitude()).append(", ")
                .append(center.getLongitude()).append("\\n");
            report.append("Zoom: ").append(readyMap.getCameraPosition().zoom).append("\\n");
            report.append("Region selection: ").append(regionPicker.getSelectedItem()).append("\\n");
            report.append("Network: run once offline and once online for comparison\\n\\n");

            SymbolLayer houseLayer = (SymbolLayer) readyStyle.getLayer("delivery-house-numbers");
            report.append("House label layer present: ").append(houseLayer != null).append("\\n");
            if (houseLayer != null) {
                report.append("House label source: ").append(houseLayer.getSourceId()).append("\\n");
                report.append("House label source layer: ")
                    .append(houseLayer.getSourceLayer()).append("\\n");
                report.append("House label minimum zoom: ").append(houseLayer.getMinZoom()).append("\\n");
            }

            for (org.maplibre.android.style.sources.Source source : readyStyle.getSources()) {
                report.append("\\nSOURCE: ").append(source.getId())
                    .append(" (").append(source.getClass().getSimpleName()).append(")\\n");
                if (!(source instanceof VectorSource)) continue;
                VectorSource vector = (VectorSource) source;
                String[] layers = {"housenumber", "building", "address", "poi"};
                for (String layerName : layers) {
                    try {
                        List<Feature> features = vector.querySourceFeatures(
                            new String[]{layerName}, null);
                        int count = features == null ? 0 : features.size();
                        report.append("  ").append(layerName).append(": ")
                            .append(count).append(" loaded features\\n");
                        if ("housenumber".equals(layerName)) houseTotal += count;
                        if ("building".equals(layerName)) buildingTotal += count;
                        int sampled = 0;
                        if (features != null) for (Feature feature : features) {
                            String props = feature.properties() == null
                                ? "{}" : feature.properties().toString();
                            if (props.contains("housenumber") || props.contains("addr:")) {
                                addressProperties++;
                                if (sampled < 5) {
                                    report.append("    ADDRESS SAMPLE: ")
                                        .append(props).append("\\n");
                                    sampled++;
                                }
                            } else if (sampled < 2 && "housenumber".equals(layerName)) {
                                report.append("    SAMPLE: ").append(props).append("\\n");
                                sampled++;
                            }
                        }
                    } catch (Exception e) {
                        report.append("  ").append(layerName).append(" query ERROR: ")
                            .append(e.toString()).append("\\n");
                    }
                }
            }
            report.append("\\nTOTALS across loaded sources (may include tile duplicates)\\n");
            report.append("housenumber features: ").append(houseTotal).append("\\n");
            report.append("building features: ").append(buildingTotal).append("\\n");
            report.append("features with address properties: ")
                .append(addressProperties).append("\\n");
            report.append("A zero count only describes currently loaded tiles,")
                .append(" NOT statewide coverage.\\n");
        } catch (Exception e) {
            report.append("\\nDIAGNOSTIC ERROR: ").append(e.toString()).append("\\n");
        }
        String result = report.toString();
        try {
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("MapLibre house diagnostics", result));
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME,
                "maplibre_house_diagnostics_" + System.currentTimeMillis() + ".txt");
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("Downloads insertion returned null");
            try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new Exception("Output stream unavailable");
                stream.write(result.getBytes(StandardCharsets.UTF_8));
            }
            status.setText("Diagnostics saved to Downloads and copied to clipboard • "
                + houseTotal + " house records, " + buildingTotal + " buildings");
        } catch (Exception e) {
            status.setText("Diagnostics copied to clipboard; file save failed: " + e.getMessage());
        }
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
            offlineStatus.setText(REGION_NAMES[index] + ": not downloaded (zoom " + (index <= 1 ? "9–16" : "9–12") + ")");
            return;
        }
        region.getStatus(new OfflineRegion.OfflineRegionStatusCallback() {
            @Override public void onStatus(OfflineRegionStatus st) {
                if (index != regionPicker.getSelectedItemPosition() || downloading) return;
                offlineStatus.setText(REGION_NAMES[index] + ": " +
                    (st.isComplete() ? "COMPLETE" : "incomplete") + " • " +
                    st.getCompletedTileCount() + " tiles • " +
                    formatSize(st.getCompletedResourceSize()) +
                    "\nConfigured zoom 9–" + (index <= 1 ? "16" : "12") +
                    " • resources " + st.getCompletedResourceCount() + "/" +
                    st.getRequiredResourceCount() +
                    "\nSDK tile count is NOT the theoretical XYZ tile count.");
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
                9.0, index <= 1 ? 16.0 : 12.0, 1.0f, false);
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
                        tiles + " SDK tiles • " + formatSize(bytes) +
                        "\nResources " + st.getCompletedResourceCount() + "/" +
                        st.getRequiredResourceCount() +
                        " • zoom 9–" + (index <= 1 ? "16" : "12") +
                        "\nVerify detail offline; tile count may reflect source maxzoom.");
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
