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
    private TextView status, offlineStatus, mileage;
    private Button downloadButton, showRouteButton, showRegionButton, diagnosticButton;
    private Spinner regionPicker;
    private final java.util.HashMap<String, OfflineRegion> savedRegions = new java.util.HashMap<>();
    private int activeRegionIndex = -1;
    private OfflineRegion offlineRegion;
    private boolean downloading;
    private boolean downloadAll;
    private boolean regionsReady;
    private Button allButton;
    private static final int FIRST_STATE_REGION = 7;
    private long batchBytes;
    private long batchTiles;
    private long downloadStart;
    private long initialBytes = -1;
    private OfflineRegionStatus lastDownloadStatus;
    private MapLibreMap readyMap;
    private Style readyStyle;
    private boolean demoRouteShown;
    private static final int IMPORT_ROUTE = 1401;
    private String pendingSlot;
    private LinearLayout controls;
    private Button menuButton;
    private final StringBuilder debugLog = new StringBuilder();
    private boolean reloading;
    private org.maplibre.android.camera.CameraPosition reloadCamera;
    private LatLngBounds routeBounds;
    private final java.util.Map<String,Button> slotButtons=new java.util.HashMap<>();
    private final String[] routeSlots = {"AM","PM","EX"};

    private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/liberty";
    private static final String[] REGION_NAMES = {
        "Athol high-detail test (z9-16)", "Greenfield detail test (z9-16)", "Western MA pilot", "Central MA pilot",
        "Eastern MA pilot", "Cape & Islands pilot", "North Quabbin measurement (z9-16)",
        "Massachusetts West (z9-16)", "Massachusetts Central (z9-16)",
        "Massachusetts Northeast (z9-16)", "Massachusetts Southeast & Islands (z9-16)"
    };
    private static final String[] REGION_IDS = {
        "athol-detail-test-v05", "greenfield-detail-test-v07", "western-ma-pilot-v04", "central-ma-pilot-v04",
        "eastern-ma-pilot-v04", "cape-islands-pilot-v04", "north-quabbin-measurement-v12",
        "ma-west-v13", "ma-central-v13", "ma-northeast-v13", "ma-southeast-v13"
    };
    // Pilot rectangles, NOT verified complete Massachusetts coverage.
    // Coordinates: south, north, west, east.
    private static final double[][] AREAS = {
        {42.58, 42.62, -72.30, -72.25},
        {42.56, 42.60, -72.62, -72.57},
        {42.00, 42.80, -73.50, -72.45},
        {42.00, 42.80, -72.45, -71.55},
        {42.00, 42.80, -71.55, -70.55},
        {41.20, 42.05, -71.30, -69.90},
        {42.48, 42.63, -72.45, -72.30},
        {42.00, 42.90, -73.55, -72.40},
        {42.00, 42.90, -72.40, -71.50},
        {42.00, 42.90, -71.50, -69.80},
        {41.15, 42.00, -71.55, -69.80}
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        registerReceiver(routeChanged,new android.content.IntentFilter("com.routeviewer.maplibre.ROUTE_CHANGED"));
        MapLibre.getInstance(this);
        org.maplibre.android.log.Logger.setVerbosity(android.util.Log.VERBOSE);
        logDebug("START v1.4.4 "+networkState());

        // Limit this application's concurrent HTTP requests and identify the client.
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(4);
        dispatcher.setMaxRequestsPerHost(4);
        OkHttpClient mapClient = new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor(chain -> {
                String url=chain.request().url().toString();
                try {
                    okhttp3.Response response=chain.proceed(chain.request().newBuilder()
                        .header("User-Agent","RichMapLibreRouteViewer/1.3 (Android)").build());
                    logDebug("HTTP "+response.code()+" "+url);
                    return response;
                } catch(java.io.IOException e){logDebug("HTTP failure "+url+" "+e); throw e;}
            })
            .build();
        HttpRequestUtil.setOkHttpClient(mapClient);
        OfflineManager.getInstance(this).setOfflineMapboxTileCountLimit(100000L);

        FrameLayout frame = new FrameLayout(this);
        mapView = new MapView(this);
        frame.addView(mapView);
        status = new TextView(this);
        status.setText("MapLibre Route Viewer v1.4.4 • AM / PM / EX");
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xBB182A36);
        status.setPadding(18, 12, 18, 12);
        frame.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        mileage=new TextView(this); mileage.setTextColor(Color.WHITE); mileage.setPadding(12,12,18,12); mileage.setBackgroundColor(0xBB182A36); frame.addView(mileage,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT));

        controls = new LinearLayout(this);
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
        LinearLayout slots = new LinearLayout(this);
        for (String slot : routeSlots) {
            Button b = new Button(this);
            slotButtons.put(slot,b);
            b.setText(RouteInstaller.label(this,slot));
            slots.addView(b, new LinearLayout.LayoutParams(0,-2,1));
            b.setOnClickListener(v -> showSavedRoute(slot));
        }
        Button importButton = new Button(this);
        importButton.setText("Import");
        slots.addView(importButton, new LinearLayout.LayoutParams(0,-2,1));
        importButton.setOnClickListener(v -> {
            android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            startActivityForResult(pick, IMPORT_ROUTE);
        });
        controls.addView(slots);
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
                .newLatLngZoom(i == 10 ? new LatLng(41.6525,-70.2881) : new LatLng((a[0]+a[1])/2, (a[2]+a[3])/2), 15.0));
            hideControls();
        });
        showRouteButton.setOnClickListener(v -> {
            if (readyMap == null || readyStyle == null) return;
            try {
                loadRoute(readyStyle, readyMap);
                demoRouteShown = true;
                pendingSlot=null;
                hideControls();
                showRouteButton.setEnabled(true);
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
        allButton = new Button(this);
        allButton.setText("Download all Massachusetts");
        allButton.setEnabled(false);
        controls.addView(allButton);
        allButton.setOnClickListener(v -> {
            if (!regionsReady || downloading || downloadAll) return;
            downloadAll = true;
            batchBytes = 0; batchTiles = 0;
            allButton.setEnabled(false);
            regionPicker.setSelection(FIRST_STATE_REGION);
            startOfflineDownload();
        });
        LinearLayout tools = new LinearLayout(this);
        for(String name:new String[]{"Reload Map","Export Debug Log","Hide"}) {
            Button b=new Button(this); b.setText(name);
            tools.addView(b,new LinearLayout.LayoutParams(0,-2,1));
            b.setOnClickListener(v -> {
                if(name.equals("Reload Map")) reloadMap();
                else if(name.equals("Export Debug Log")) exportDebug();
                else hideControls();
            });
        }
        controls.addView(tools);
        controls.addView(offlineStatus);
        regionPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                    android.view.View view, int position, long id) {
                if (!downloading) refreshSelectedRegion();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        frame.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LinearLayout floating=new LinearLayout(this);
        menuButton=new Button(this); menuButton.setText("Menu");
        menuButton.setOnClickListener(v -> {controls.setVisibility(android.view.View.VISIBLE); floating.setVisibility(android.view.View.GONE);});
        floating.addView(menuButton);
        Button fit=new Button(this); fit.setText("Fit Route");
        fit.setOnClickListener(v -> {if(readyMap!=null && routeBounds!=null) try {readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder(readyMap.getCameraPosition()).bearing(0).build()));readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(routeBounds,90));} catch(Exception e){logDebug("Fit error "+e);}});
        floating.addView(fit);
        frame.addView(floating,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT));
        floating.setVisibility(android.view.View.GONE);
        menuButton.setTag(floating);
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
            map.getUiSettings().setRotateGesturesEnabled(true);
            map.getUiSettings().setCompassEnabled(true);
            map.addOnCameraIdleListener(() -> updateHouseDensity());
            map.setMaxPitchPreference(0.0);
            readyMap=map;
            mapView.addOnDidFailLoadingMapListener(error -> logDebug("MAP LOAD FAILURE "+error));
            mapView.addOnDidFinishLoadingMapListener(() -> logDebug("MAP LOAD FINISHED"));
            mapView.addOnDidFinishRenderingMapListener(full -> logDebug("MAP RENDER full="+full));
            loadMapStyle(map);
        });
        checkOfflineRegions();
    }

    private void loadMapStyle(MapLibreMap map) {
        logDebug("STYLE requested "+networkState());
        String pinned = offlineStyleJson();
        Style.Builder builder = pinned == null ? new Style.Builder().fromUri(STYLE_URL) : new Style.Builder().fromJson(pinned);
        map.setStyle(builder, style -> {
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
                        PropertyFactory.textField(Expression.get("housenumber")),
                        PropertyFactory.textFont(new String[]{"Noto Sans Regular"}),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor("#333333"),
                        PropertyFactory.textHaloColor("#FFFFFF"),
                        PropertyFactory.textHaloWidth(1.2f),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true)
                    );
                    style.addLayer(houseNumbers);
                }
                readyMap = map;
                readyStyle = style;
                updateHouseDensity();
                showRouteButton.setEnabled(true);
                showRegionButton.setEnabled(true);
                diagnosticButton.setEnabled(true);
                status.setText("v1.4.4 • Import route JSON or select AM / PM / EX");
                logDebug("STYLE ready sources="+style.getSources().size());
                if (pendingSlot != null) showSavedRoute(pendingSlot);
                else if(demoRouteShown) try {loadRoute(style,map);} catch(Exception e){logDebug("Demo restore "+e);}
                if(reloadCamera!=null) map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(reloadCamera));
                reloadCamera=null; reloading=false;
                mapView.postDelayed(() -> snapshotDebug("style + 5 seconds"),5000);
            });
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
            report.append("MAPLIBRE HOUSE NUMBER DIAGNOSTICS v1.3\\n");
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

            try {
                List<Feature> rendered = readyMap.queryRenderedFeatures(
                    new android.graphics.RectF(0, 0, mapView.getWidth(), mapView.getHeight()),
                    "delivery-house-numbers");
                report.append("Rendered house-number features: ").append(rendered.size()).append("\n");
            } catch (Exception e) {
                report.append("Rendered query ERROR: ").append(e.toString()).append("\n");
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
                    regionsReady = true;
                    allButton.setEnabled(true);
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
        downloadButton.setEnabled(regionsReady && !downloadAll);
        downloadButton.setText(region == null ? "Download selected region" : "Check / resume region");
        if (region == null) {
            offlineStatus.setText(REGION_NAMES[index] + ": not downloaded (zoom " + ((index <= 1 || index >= 6) ? "9–16" : "9–12") + ")");
            return;
        }
        region.getStatus(new OfflineRegion.OfflineRegionStatusCallback() {
            @Override public void onStatus(OfflineRegionStatus st) {
                if (index != regionPicker.getSelectedItemPosition() || downloading || downloadAll) return;
                offlineStatus.setText(REGION_NAMES[index] + ": " +
                    (st.isComplete() ? "COMPLETE" : "incomplete") + " • " +
                    st.getCompletedTileCount() + " tiles • " +
                    formatSize(st.getCompletedResourceSize()) +
                    "\nConfigured zoom 9–" + ((index <= 1 || index >= 6) ? "16" : "12") +
                    " • resources " + st.getCompletedResourceCount() + "/" +
                    st.getRequiredResourceCount() +
                    "\nSDK tile count is NOT the theoretical XYZ tile count.");
                downloadButton.setText(st.isComplete() ? "Region already saved" : "Resume selected region");
                downloadButton.setEnabled(regionsReady && !downloadAll && !st.isComplete());
            }
            @Override public void onError(String error) {
                offlineStatus.setText("Status error: " + error);
            }
        });
    }

    private void startOfflineDownload() {
        if (downloading || !regionsReady) return;
        int index = regionPicker.getSelectedItemPosition();
        if (index < 0 || index >= REGION_IDS.length) return;
        if (getFilesDir().getUsableSpace() < 512L * 1024 * 1024) {
            downloadAll = false;
            allButton.setEnabled(true);
            offlineStatus.setText("Download stopped: less than 512 MiB free storage");
            return;
        }
        allButton.setEnabled(false);
        downloading = true;
        downloadStart = android.os.SystemClock.elapsedRealtime();
        initialBytes = -1;
        lastDownloadStatus = null;
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
                        advanceBatch(index, st);
                    } else {
                        initialBytes = st.getCompletedResourceSize();
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
                9.0, (index <= 1 || index >= 6) ? 16.0 : 12.0, 1.0f, false);
        OfflineManager.getInstance(this).createOfflineRegion(definition,
            REGION_IDS[index].getBytes(StandardCharsets.UTF_8),
            new OfflineManager.CreateOfflineRegionCallback() {
                @Override public void onCreate(OfflineRegion region) {
                    savedRegions.put(REGION_IDS[index], region);
                    initialBytes = 0;
                    activateRegion(region, index);
                }
                @Override public void onError(String error) {
                    finishWithError("Create error: " + error);
                }
            });
    }

    private void finishWithError(String message) {
        downloadAll = false;
        allButton.setEnabled(regionsReady);
        if (activeRegionIndex >= 0) saveDownloadReport(activeRegionIndex, lastDownloadStatus, message);
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
                lastDownloadStatus = st;
                long tiles = st.getCompletedTileCount();
                long bytes = st.getCompletedResourceSize();
                if (st.isComplete()) {
                    saveDownloadReport(index, st, "COMPLETE");
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                    downloading = false;
                    activeRegionIndex = -1;
                    regionPicker.setEnabled(true);
                    offlineStatus.setText(REGION_NAMES[index] + " COMPLETE: " +
                        tiles + " SDK tiles • " + formatSize(bytes) +
                        "\nResources " + st.getCompletedResourceCount() + "/" +
                        st.getRequiredResourceCount() +
                        " • zoom 9–" + ((index <= 1 || index >= 6) ? "16" : "12") +
                        "\nVerify detail offline; tile count may reflect source maxzoom.");
                    downloadButton.setText("Region already saved");
                    downloadButton.setEnabled(false);
                    advanceBatch(index, st);
                } else if (getFilesDir().getUsableSpace() < 256L * 1024 * 1024 || tiles >= (index >= 7 ? 50000 : index == 6 ? 15000 : 2900) || bytes >= (index >= 7 ? 1024L : index == 6 ? 256L : 48L) * 1024 * 1024) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                    finishWithError("Stopped near per-download limit: " +
                        tiles + " tiles • " + formatSize(bytes));
                } else {
                    offlineStatus.setText((downloadAll ? "Massachusetts region " + (index-FIRST_STATE_REGION+1) + "/4 • " : "") + REGION_NAMES[index] + ": " +
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

    private void advanceBatch(int index, OfflineRegionStatus st) {
        if (!downloadAll) { allButton.setEnabled(regionsReady); return; }
        batchBytes += st.getCompletedResourceSize(); batchTiles += st.getCompletedTileCount();
        if (index + 1 < REGION_IDS.length) {
            regionPicker.setSelection(index + 1);
            regionPicker.post(() -> { if (downloadAll && !downloading) startOfflineDownload(); });
        } else {
            downloadAll = false;
            allButton.setEnabled(true);
            String summary = "Massachusetts: 4/4 regions COMPLETE • " + batchTiles + " summed SDK tiles • " + formatSize(batchBytes) + " summed resource sizes (shared resources may repeat)";
            offlineStatus.setText(summary);
            status.setText(summary);
            ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Massachusetts download summary",summary));
        }
    }

    private void saveDownloadReport(int index, OfflineRegionStatus st, String outcome) {
        double[] a = AREAS[index];
        double area = 6371.0 * 6371.0 * Math.toRadians(a[3]-a[2]) * (Math.sin(Math.toRadians(a[1]))-Math.sin(Math.toRadians(a[0])));
        double seconds = Math.max(0.001, (android.os.SystemClock.elapsedRealtime()-downloadStart)/1000.0);
        StringBuilder b = new StringBuilder("MAPLIBRE DOWNLOAD REPORT v1.3\n");
        b.append("Region: ").append(REGION_NAMES[index]).append("\nBounds S,N,W,E: ").append(java.util.Arrays.toString(a));
        b.append(String.format(Locale.US,"\nArea: %.2f km2\nZoom: 9-%d\nElapsed session seconds: %.2f\n",area,(index <= 1 || index >= 6)?16:12,seconds));
        b.append("Outcome: ").append(outcome).append("\n");
        if (st != null) {
            long bytes = st.getCompletedResourceSize();
            b.append("Resources: ").append(st.getCompletedResourceCount()).append("/").append(st.getRequiredResourceCount()).append("\n");
            b.append("Required count precise: ").append(st.isRequiredResourceCountPrecise()).append("\n");
            b.append("SDK tiles: ").append(st.getCompletedTileCount()).append("\nRegion resource bytes: ").append(bytes).append("\n");
            if(initialBytes >= 0) b.append(String.format(Locale.US,"Session resource growth: %d bytes\nAverage resource growth: %.2f KiB/s\n",Math.max(0,bytes-initialBytes),Math.max(0,bytes-initialBytes)/1024.0/seconds));
        }
        b.append("Database files (including sidecars, if present):\n");
        appendDatabaseFiles(getFilesDir(), b, 3);
        appendDatabaseFiles(getCacheDir(), b, 3);
        appendDatabaseFiles(new java.io.File(getApplicationInfo().dataDir,"databases"), b, 3);
        String result = b.toString();
        try {
            ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MapLibre download report",result));
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME,"maplibre_download_"+System.currentTimeMillis()+".txt");
            values.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(uri == null) throw new Exception("Unable to create report");
            try(java.io.OutputStream out = getContentResolver().openOutputStream(uri)) { if(out == null) throw new Exception("Unable to open report"); out.write(result.getBytes(StandardCharsets.UTF_8)); }
            status.setText("Download report saved to Downloads and clipboard");
        } catch(Exception e) { status.setText("Download report export error: "+e.getMessage()); }
    }

    private void appendDatabaseFiles(java.io.File dir, StringBuilder b, int depth) {
        if(dir == null || depth < 0) return;
        java.io.File[] files = dir.listFiles();
        if(files == null) return;
        for(java.io.File f : files) {
            if(f.isDirectory()) appendDatabaseFiles(f,b,depth-1);
            else if(f.getName().matches("(?i).*(\\.db|\\.sqlite|\\.sqlite3)(-wal|-shm|-journal)?$")) b.append(f.getAbsolutePath()).append(": ").append(f.length()).append(" bytes\n");
        }
    }

    // Proven Build #10 route rendering retained without behavior changes.
    private void loadRoute(Style style, MapLibreMap map) throws Exception {
        JSONObject route = new JSONObject(readAsset("route_demo.json"));
        clearRouteLayers(style);
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
        routeBounds=bounds.build();
        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
            .newLatLngBounds(routeBounds, 90));
        status.setText(stops.length() + " numbered stops • overlap enabled • demo route");
    }


    private void clearRouteLayers(Style style) {
        for (String id : new String[]{"numbered-stops","here-route-line","tomtom-route-line"}) {
            if (style.getLayer(id) != null) style.removeLayer(id);
        }
        for (String id : new String[]{"stops-source","route-source"}) {
            if (style.getSource(id) != null) style.removeSource(id);
        }
    }

    private Point checkedPoint(double lat, double lon) throws Exception {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180)
            throw new Exception("Invalid coordinate");
        return Point.fromLngLat(lon,lat);
    }

    private JSONObject validateRoute(String raw) throws Exception {
        JSONObject route = new JSONObject(raw);
        String slot = route.getString("route");
        if (!java.util.Arrays.asList(routeSlots).contains(slot)) throw new Exception("Expected AM, PM or EX");
        if (!"tomtom".equals(route.getString("provider"))) throw new Exception("Expected TomTom provider");
        JSONArray stops = route.getJSONArray("stops"), legs = route.getJSONArray("legs");
        if (stops.length()<1 || stops.length()>150 || legs.length()!=stops.length()+(route.has("shop")?1:-1))
            throw new Exception("Invalid stop or leg count");
        for(int i=0;i<stops.length();i++){
            JSONObject s=stops.getJSONObject(i);
            checkedPoint(s.getDouble("lat"),s.getDouble("lon"));
            s.getString("address");
        }
        for(int i=0;i<legs.length();i++){
            JSONObject leg=legs.getJSONObject(i);
            JSONArray pts=leg.getJSONArray("points");
            if(pts.length()==0 && leg.getJSONObject("summary").getLong("lengthInMeters")!=0)
                throw new Exception("Missing geometry for leg "+(i+1));
            for(int j=0;j<pts.length();j++){
                JSONObject p=pts.getJSONObject(j);
                checkedPoint(p.getDouble("latitude"),p.getDouble("longitude"));
            }
        }
        return route;
    }

    private void updateHouseDensity(){
        if(readyStyle==null || readyMap==null)return;
        Layer layer=readyStyle.getLayer("delivery-house-numbers");
        if(layer instanceof SymbolLayer){double z=readyMap.getCameraPosition().zoom; layer.setProperties(PropertyFactory.textAllowOverlap(z>=17),PropertyFactory.textPadding(z<16?2f:0f));}
    }
    private void installRoute(String raw) throws Exception {
        JSONObject route=RouteInstaller.validate(raw);
        String slot=route.getString("route");
        android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(getFilesDir(),"route_"+slot+".json"));
        java.io.FileOutputStream out=file.startWrite();
        try {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch(Exception e) { file.failWrite(out); throw e; }
        refreshSlotLabels();
        pendingSlot=slot;
        if(readyStyle!=null) showSavedRoute(slot);
        else status.setText(slot+" saved; waiting for map");
    }

    private void showSavedRoute(String slot) {
        try {
            android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(getFilesDir(),"route_"+slot+".json"));
            JSONObject route=RouteInstaller.validate(new String(file.readFully(),StandardCharsets.UTF_8));
            pendingSlot=slot;
            demoRouteShown=false;
            if(readyStyle==null || readyMap==null) return;
            JSONArray stops=route.getJSONArray("stops"), legs=route.getJSONArray("legs");
            List<Feature> markers=new ArrayList<>(), lines=new ArrayList<>();
            LatLngBounds.Builder bounds=new LatLngBounds.Builder();
            clearRouteLayers(readyStyle);
            for(int i=0;i<stops.length();i++){
                JSONObject s=stops.getJSONObject(i);
                double lat=s.getDouble("lat"),lon=s.getDouble("lon");
                int count=1;
                boolean later=false;
                for(int j=0;j<stops.length();j++){
                    if(j==i) continue;
                    JSONObject other=stops.getJSONObject(j);
                    if(lat==other.getDouble("lat") && lon==other.getDouble("lon")){
                        if(j<i) later=true;
                        else count++;
                    }
                }
                if(later) continue;
                String icon="installed_stop_"+(i+1)+"_"+count;
                readyStyle.addImage(icon,numberIcon(i+1,count));
                Feature f=Feature.fromGeometry(checkedPoint(lat,lon));
                f.addStringProperty("icon",icon);
                f.addStringProperty("address",s.getString("address"));
                f.addNumberProperty("number",i+1);
                markers.add(f);
                bounds.include(new LatLng(lat,lon));
            }
            JSONObject shop=route.optJSONObject("shop");
            if(shop!=null){double lat=shop.getDouble("lat"),lon=shop.getDouble("lon");
                readyStyle.addImage("shop_marker",numberIcon(0,1));
                Feature f=Feature.fromGeometry(checkedPoint(lat,lon)); f.addStringProperty("icon","shop_marker"); markers.add(f); bounds.include(new LatLng(lat,lon));}
            int points=0;
            for(int i=0;i<legs.length();i++){
                JSONArray pts=legs.getJSONObject(i).getJSONArray("points");
                List<Point> coords=new ArrayList<>();
                for(int j=0;j<pts.length();j++){
                    JSONObject p=pts.getJSONObject(j);
                    double lat=p.getDouble("latitude"),lon=p.getDouble("longitude");
                    coords.add(checkedPoint(lat,lon));
                    bounds.include(new LatLng(lat,lon));
                    points++;
                }
                if(coords.size()>=2){
                    Feature f=Feature.fromGeometry(LineString.fromLngLats(coords));
                    f.addNumberProperty("leg",i+1);
                    lines.add(f);
                }
            }
            readyStyle.addSource(new GeoJsonSource("route-source",FeatureCollection.fromFeatures(lines)));
            LineLayer line=new LineLayer("tomtom-route-line","route-source");
            line.withProperties(PropertyFactory.lineColor("#1769AA"),PropertyFactory.lineWidth(4f));
            readyStyle.addLayer(line);
            readyStyle.addSource(new GeoJsonSource("stops-source",FeatureCollection.fromFeatures(markers)));
            SymbolLayer layer=new SymbolLayer("numbered-stops","stops-source");
            layer.withProperties(PropertyFactory.iconImage(Expression.get("icon")),PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),PropertyFactory.iconSize(1f));
            readyStyle.addLayer(layer);
            try {
                routeBounds=bounds.build();
                readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder(readyMap.getCameraPosition()).bearing(0).build()));
                readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(routeBounds,90));
            } catch(Exception e) {
                JSONObject s=stops.getJSONObject(0);
                readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(new LatLng(s.getDouble("lat"),s.getDouble("lon")),16));
            }
            long meters=0; for(int i=0;i<legs.length();i++) meters+=legs.getJSONObject(i).getJSONObject("summary").getLong("lengthInMeters");
            status.setText(RouteInstaller.label(this,slot)+" • "+stops.length()+" stops");
            status.setGravity(Gravity.LEFT);
            mileage.setText(String.format(Locale.US,"%.1f mi",meters/1609.344));
            logDebug("ROUTE "+slot+" stops="+stops.length()+" markers="+markers.size()+" points="+points);
            hideControls();
            mapView.postDelayed(() -> snapshotDebug("route + 5 seconds"),5000);
        } catch(Exception e) { status.setText(slot+" route unavailable: "+e.getMessage()); }
    }

    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_ROUTE || result!=RESULT_OK || data==null || data.getData()==null) return;
        try(InputStream in=getContentResolver().openInputStream(data.getData());
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null) throw new Exception("Cannot open route");
            byte[] buffer=new byte[8192]; int n;
            while((n=in.read(buffer))!=-1){
                if(out.size()+n>8*1024*1024) throw new Exception("Route file too large");
                out.write(buffer,0,n);
            }
            installRoute(out.toString("UTF-8"));
        } catch(Exception e){status.setText("Import failed: "+e.getMessage());}
    }

    private Bitmap numberIcon(int n) { return numberIcon(n,1); }
    private Bitmap numberIcon(int n,int count) {
        Bitmap b = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(n==0?0xFF278344:0xFF1A6DB3); c.drawCircle(44, 44, 39, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6);
        p.setColor(Color.WHITE); c.drawCircle(44, 44, 36, p);
        p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        p.setTextSize(n >= 100 ? 31 : 39);
        c.drawText(n==0?"S":String.valueOf(n), 44, 57, p);
        if(count>1){
            p.setColor(Color.WHITE); c.drawCircle(72,16,16,p);
            p.setColor(0xFFF5B82E); c.drawCircle(72,16,14,p);
            p.setColor(0xFF222222); p.setTextSize(count>=10?15:21);
            c.drawText(String.valueOf(count),72,23,p);
        }
        return b;
    }


    private void hideControls(){
        controls.setVisibility(android.view.View.GONE);
        ((android.view.View)menuButton.getTag()).setVisibility(android.view.View.VISIBLE);
    }
    private synchronized void logDebug(String s){
        if(debugLog.length()>250000) debugLog.delete(0,50000);
        debugLog.append(new java.text.SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new java.util.Date())).append(" ").append(s).append("\n");
    }
    private String networkState(){
        try {
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni=cm.getActiveNetworkInfo();
            return ni==null?"network=none":"network="+ni.getTypeName()+" connected="+ni.isConnected();
        } catch(Exception e){return "network unknown "+e;}
    }
    private void snapshotDebug(String reason){
        logDebug("SNAPSHOT "+reason+" "+networkState());
        if(readyMap!=null) logDebug("CAMERA "+readyMap.getCameraPosition());
        if(readyStyle!=null) for(org.maplibre.android.style.sources.Source source:readyStyle.getSources()){
            logDebug("SOURCE "+source.getId());
            if(source instanceof VectorSource) for(String layer:new String[]{"transportation","water","building","housenumber","poi"}) try{
                logDebug("  "+layer+"="+((VectorSource)source).querySourceFeatures(new String[]{layer},null).size());
            }catch(Exception e){logDebug("query "+e);}
        }
        StringBuilder db=new StringBuilder(); appendDatabaseFiles(getFilesDir(),db,3); logDebug(db.toString());
        for(String id:new ArrayList<>(savedRegions.keySet())){
            OfflineRegion region=savedRegions.get(id);
            region.getStatus(new OfflineRegion.OfflineRegionStatusCallback(){
                @Override public void onStatus(OfflineRegionStatus s){logDebug("REGION "+id+" complete="+s.isComplete()+" tiles="+s.getCompletedTileCount()+" bytes="+s.getCompletedResourceSize()+" resources="+s.getCompletedResourceCount()+"/"+s.getRequiredResourceCount());}
                @Override public void onError(String e){logDebug("REGION "+id+" "+e);}
            });
        }
    }
    private void reloadMap(){
        if(readyMap==null || downloading || reloading) return;
        snapshotDebug("before reload");
        reloadCamera=readyMap.getCameraPosition();
        reloading=true; readyStyle=null;
        status.setText("Reloading map...");
        loadMapStyle(readyMap);
        mapView.postDelayed(() -> {if(reloading){reloading=false;logDebug("RELOAD timeout");status.setText("Reload timed out; export debug log");}},15000);
    }
    private void exportDebug(){
        snapshotDebug("export");
        String currentStyle = readyStyle == null ? "" : readyStyle.getJson();
        Toast.makeText(this,"Inspecting offline database...",Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            inspectDatabase(currentStyle);
            runOnUiThread(() -> mapView.postDelayed(() -> {
            String result;
            synchronized(this){result=debugLog.toString();}
            try{
                ContentValues values=new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME,"maplibre_debug_"+System.currentTimeMillis()+".txt");
                values.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(uri==null) throw new Exception("Cannot create log");
                try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){out.write(result.getBytes(StandardCharsets.UTF_8));}
                Toast.makeText(this,"Debug log saved to Downloads",Toast.LENGTH_LONG).show();
            }catch(Exception e){status.setText("Export error "+e);}
        },1200));
        }).start();
    }


    private void dbQuery(android.database.sqlite.SQLiteDatabase db,StringBuilder b,String sql){
        b.append("\nSQL ").append(sql).append("\n");
        try(android.database.Cursor cur=db.rawQuery(sql,null)){
            b.append(java.util.Arrays.toString(cur.getColumnNames())).append("\n");
            int rows=0;
            while(cur.moveToNext() && rows++<100){
                for(int i=0;i<cur.getColumnCount();i++){
                    if(i>0)b.append(" | ");
                    if(cur.getType(i)==android.database.Cursor.FIELD_TYPE_BLOB)b.append("<blob>");
                    else b.append(cur.getString(i));
                }
                b.append("\n");
            }
        }catch(Exception e){b.append("QUERY ERROR ").append(e).append("\n");}
    }
    private void inspectDatabase(String styleJson){
        StringBuilder b=new StringBuilder("\nOFFLINE DATABASE READ-ONLY INSPECTION v1.4.4\n");
        try{
            JSONObject sources=new JSONObject(styleJson).optJSONObject("sources");
            b.append("CURRENT STYLE SOURCES: ").append(sources).append("\n");
        }catch(Exception e){b.append("Style JSON error ").append(e).append("\n");}
        java.io.File file=new java.io.File(getFilesDir(),"mbgl-offline.db");
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY|android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            dbQuery(db,b,"PRAGMA quick_check");
            dbQuery(db,b,"SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name");
            dbQuery(db,b,"SELECT url_template,pixel_ratio,z,COUNT(*) AS tiles FROM tiles GROUP BY url_template,pixel_ratio,z ORDER BY url_template,z");
            dbQuery(db,b,"SELECT url_template,pixel_ratio,z,x,y,length(data) AS bytes FROM tiles ORDER BY z DESC LIMIT 15");
            dbQuery(db,b,"SELECT region_id,COUNT(*) AS tile_links FROM region_tiles GROUP BY region_id");
            dbQuery(db,b,"SELECT rt.region_id,t.z,COUNT(*) AS tiles FROM region_tiles rt JOIN tiles t ON t.id=rt.tile_id GROUP BY rt.region_id,t.z ORDER BY rt.region_id,t.z");
            dbQuery(db,b,"SELECT id,definition FROM regions");
            dbQuery(db,b,"SELECT kind,COUNT(*) FROM resources GROUP BY kind");
            dbQuery(db,b,"SELECT url,kind,length(data) AS bytes FROM resources WHERE url LIKE '%style%' OR url LIKE '%tilejson%' OR url LIKE '%/planet%' LIMIT 60");
            // TileJSON is often compressed in the native cache; decode only small JSON resources.
            try(android.database.Cursor cur=db.rawQuery("SELECT url,data,compressed FROM resources WHERE kind IN (1,2) AND length(data)<2000000 LIMIT 30",null)){
                while(cur.moveToNext()){
                    byte[] bytes=cur.getBlob(1); if(bytes==null)continue;
                    try{
                        if(cur.getInt(2)!=0){
                            java.io.InputStream in=new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bytes));
                            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
                            while((n=in.read(buf))!=-1){if(out.size()+n>4000000)throw new Exception("JSON too large");out.write(buf,0,n);}in.close();bytes=out.toByteArray();
                        }
                        JSONObject j=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                        b.append("CACHED JSON ").append(cur.getString(0)).append(" tiles=").append(j.optJSONArray("tiles")).append(" sources=").append(j.optJSONObject("sources")).append("\n");
                    }catch(Exception e){b.append("Cached JSON decode ").append(cur.getString(0)).append(" ").append(e).append("\n");}
                }
            }catch(Exception e){b.append("Cached JSON query ").append(e).append("\n");}
            // Check actual z15 tile containing downtown Hyannis, including parent tiles.
            for(int z=9;z<=16;z++){
                double lat=41.6525,lon=-70.2881;int n=1<<z;
                int x=(int)Math.floor((lon+180)/360*n);
                int y=(int)Math.floor((1-Math.log(Math.tan(Math.toRadians(lat))+1/Math.cos(Math.toRadians(lat)))/Math.PI)/2*n);
                dbQuery(db,b,"SELECT url_template,z,x,y,length(data) FROM tiles WHERE z="+z+" AND x="+x+" AND y="+y);
            }
        }catch(Exception e){b.append("DATABASE OPEN ERROR ").append(e).append("\n");}
        try{
            Process p=new ProcessBuilder("logcat","-d","-t","1500","--pid="+android.os.Process.myPid()).redirectErrorStream(true).start();
            try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))){
                String line;int count=0;
                while((line=reader.readLine())!=null && count<300){
                    String lower=line.toLowerCase(Locale.US);
                    if(lower.contains("maplibre")||lower.contains("mbgl")||lower.contains("tile")||lower.contains("offline")){b.append(line).append("\n");count++;}
                }
            }finally{p.destroy();}
        }catch(Exception e){b.append("Native log unavailable ").append(e).append("\n");}
        logDebug(b.toString());
    }


    // Use the exact tile cache key associated with the largest installed coverage.
    // Read only: never rename tiles, alter regions, or delete the database.
    private String offlineStyleJson() {
        java.io.File file = new java.io.File(getFilesDir(),"mbgl-offline.db");
        if(!file.exists()) return null;
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY|android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            String template;
            try(android.database.Cursor cur=db.rawQuery("SELECT t.url_template,COUNT(*) AS n FROM region_tiles rt JOIN tiles t ON t.id=rt.tile_id WHERE t.url_template LIKE 'https://tiles.openfreemap.org/%' AND t.url_template LIKE '%.pbf' AND length(t.data)>0 GROUP BY t.url_template ORDER BY n DESC LIMIT 1",null)){
                if(!cur.moveToFirst()) {logDebug("PIN no downloaded vector tiles");return null;}
                template=cur.getString(0);
                logDebug("PIN selected "+template+" region links="+cur.getLong(1));
            }
            JSONObject json=readCachedJson(db,STYLE_URL);
            JSONObject source=json.getJSONObject("sources").getJSONObject("openmaptiles");
            String sourceUrl=template.contains("/backup_planet/")?"https://tiles.openfreemap.org/backup_planet":"https://tiles.openfreemap.org/planet";
            // Inline the exact template to avoid a mutable TileJSON redirect.
            // Copy source metadata only if the cached TileJSON matches this template.
            int maxZoom=14;
            int minZoom=0;
            try{
                JSONObject tileJson=readCachedJson(db,sourceUrl);
                JSONArray urls=tileJson.optJSONArray("tiles");
                if(urls!=null && template.equals(urls.optString(0))){
                    maxZoom=tileJson.optInt("maxzoom",14);
                    minZoom=tileJson.optInt("minzoom",0);
                    if(tileJson.has("attribution"))source.put("attribution",tileJson.get("attribution"));
                    if(tileJson.has("bounds"))source.put("bounds",tileJson.get("bounds"));
                    if(tileJson.has("scheme"))source.put("scheme",tileJson.get("scheme"));
                }
            }catch(Exception e){logDebug("PIN TileJSON metadata fallback "+e);}
            source.remove("url");
            source.put("tiles",new JSONArray().put(template));
            source.put("minzoom",minZoom);
            source.put("maxzoom",maxZoom);
            logDebug("PIN style source "+source);
            return json.toString();
        }catch(Exception e){logDebug("PIN failed; using original style "+e);return null;}
    }
    private JSONObject readCachedJson(android.database.sqlite.SQLiteDatabase db,String url) throws Exception {
        try(android.database.Cursor cur=db.rawQuery("SELECT data,compressed FROM resources WHERE url=?",new String[]{url})){
            if(!cur.moveToFirst() || cur.isNull(0))throw new java.io.IOException("Missing cached resource "+url);
            byte[] bytes=cur.getBlob(0);
            if(cur.getInt(1)!=0){
                try(java.io.InputStream in=new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bytes)); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[4096];int n;
                    while((n=in.read(buf))!=-1){if(out.size()+n>4000000)throw new java.io.IOException("Cached JSON too large");out.write(buf,0,n);}
                    bytes=out.toByteArray();
                }
            }
            return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        }
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
    private final android.content.BroadcastReceiver routeChanged=new android.content.BroadcastReceiver(){
 @Override public void onReceive(Context c,android.content.Intent i){refreshSlotLabels();if(i.getStringExtra("route").equals(pendingSlot))showSavedRoute(pendingSlot);}
 };
 private void refreshSlotLabels(){for(String s:routeSlots){Button b=slotButtons.get(s);if(b!=null)b.setText(RouteInstaller.label(this,s));}}
    @Override protected void onResume() { super.onResume(); mapView.onResume(); refreshSlotLabels(); }
    @Override protected void onPause() { mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override protected void onDestroy() { unregisterReceiver(routeChanged); mapView.onDestroy(); super.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); mapView.onSaveInstanceState(state);
    }
}
