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
    private FrameLayout rootFrame;
    private LinearLayout deliveryPanel, deliveryRows;
    private android.widget.ScrollView deliveryScroll;
    private TextView deliveryGrab;
    private JSONObject deliveryRoute;
    private String deliveryKey="";
    private int nextStop=0, rowDp=56, fontSp=18, panelDp=0;
    private boolean panelCollapsed=false, restoringView=false;
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private android.content.SharedPreferences prefs(){return getPreferences(MODE_PRIVATE);}
    private void saveView(){
        if(readyMap==null || restoringView || readyStyle==null)return;
        org.maplibre.android.camera.CameraPosition p=readyMap.getCameraPosition();
        if(p.target==null)return;
        prefs().edit().putString("view_slot",pendingSlot==null?"":pendingSlot)
            .putString("view_lat",Double.toString(p.target.getLatitude()))
            .putString("view_lon",Double.toString(p.target.getLongitude()))
            .putString("view_zoom",Double.toString(p.zoom))
            .putString("view_bearing",Double.toString(p.bearing))
            .putBoolean("view_rotate",rotationUnlocked).apply();
    }
    private void restoreView(){
        try{
            if(!prefs().contains("view_lat"))return;
            rotationUnlocked=prefs().getBoolean("view_rotate",false);
            readyMap.getUiSettings().setRotateGesturesEnabled(rotationUnlocked);
            readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                new org.maplibre.android.camera.CameraPosition.Builder()
                .target(new LatLng(Double.parseDouble(prefs().getString("view_lat","40.6")),Double.parseDouble(prefs().getString("view_lon","-67.5"))))
                .zoom(Double.parseDouble(prefs().getString("view_zoom","7")))
                .bearing(Double.parseDouble(prefs().getString("view_bearing","0"))).tilt(0).build()));
            updateCompass();
        }catch(Exception e){logDebug("Restore view "+e);}
    }
    private void createDeliveryPanel(FrameLayout frame){
        rootFrame=frame;
        rowDp=prefs().getInt("row_dp",56);fontSp=prefs().getInt("font_sp",18);
        panelDp=prefs().getInt("panel_dp",0);panelCollapsed=prefs().getBoolean("panel_collapsed",false);
        deliveryPanel=new LinearLayout(this);deliveryPanel.setOrientation(LinearLayout.VERTICAL);
        deliveryPanel.setBackgroundColor(Color.WHITE);
        deliveryScroll=new android.widget.ScrollView(this);
        deliveryRows=new LinearLayout(this);deliveryRows.setOrientation(LinearLayout.VERTICAL);
        deliveryScroll.addView(deliveryRows);deliveryPanel.addView(deliveryScroll,new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(200),-1,Gravity.RIGHT|Gravity.TOP);
        lp.topMargin=0;lp.bottomMargin=0;
        frame.addView(deliveryPanel,lp);
        deliveryGrab=new TextView(this);deliveryGrab.setText("⋮");deliveryGrab.setTextSize(28);deliveryGrab.setGravity(Gravity.CENTER);
        deliveryGrab.setTextColor(Color.WHITE);deliveryGrab.setBackgroundColor(0xFF34434F);
        FrameLayout.LayoutParams gp=new FrameLayout.LayoutParams(dp(36),dp(100),Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        frame.addView(deliveryGrab,gp);wireGrab(deliveryGrab);
        deliveryPanel.setVisibility(android.view.View.GONE);deliveryGrab.setVisibility(android.view.View.GONE);
        frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob)->{if(r-l!=orr-ol || b-t!=ob-ot)layoutDeliveryPanel();});
    }
    private void layoutDeliveryPanel(){
        if(rootFrame==null || deliveryPanel==null)return;
        int w=rootFrame.getWidth();if(w<=0)return;
        int width=Math.min(Math.max(dp(140),panelDp==0?(int)(w*.22):dp(panelDp)),Math.max(dp(140),w-dp(72)));
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)deliveryPanel.getLayoutParams();lp.width=width;deliveryPanel.setLayoutParams(lp);
        FrameLayout.LayoutParams gp=(FrameLayout.LayoutParams)deliveryGrab.getLayoutParams();gp.rightMargin=panelCollapsed?0:width;deliveryGrab.setLayoutParams(gp);
        deliveryPanel.setVisibility(deliveryRoute!=null&&!panelCollapsed?android.view.View.VISIBLE:android.view.View.GONE);
        deliveryGrab.setVisibility(deliveryRoute!=null?android.view.View.VISIBLE:android.view.View.GONE);
    }
    private void wireGrab(android.view.View v){
        v.setOnTouchListener(new android.view.View.OnTouchListener(){
            float x,y;int start;boolean moved,wasCollapsed;
            public boolean onTouch(android.view.View view,android.view.MotionEvent e){
                switch(e.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN:
                    x=e.getRawX();y=e.getRawY();start=deliveryPanel.getWidth();moved=false;wasCollapsed=panelCollapsed;return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    float delta=e.getRawX()-x;
                    if(!moved && Math.abs(e.getRawY()-y)>dp(12) && Math.abs(delta)<dp(10))return false;
                    if(Math.abs(delta)>dp(8))moved=true;
                    if(moved){int width=(wasCollapsed?dp(36):start)-(int)delta;
                        panelCollapsed=width<dp(100);
                        if(!panelCollapsed)panelDp=Math.round(Math.max(dp(140),width)/getResources().getDisplayMetrics().density);
                        layoutDeliveryPanel();}
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if(!moved && view==deliveryGrab)panelCollapsed=!panelCollapsed;
                    prefs().edit().putInt("panel_dp",panelDp).putBoolean("panel_collapsed",panelCollapsed).apply();layoutDeliveryPanel();return true;
                case android.view.MotionEvent.ACTION_CANCEL:return true;
                }return true;
            }
        });
    }
    private void populateDelivery(JSONObject route,String slot){
        String key=slot+"_"+route.optString("date","")+"_"+Integer.toHexString(route.optJSONArray("stops").toString().hashCode());
        boolean same=key.equals(deliveryKey);
        deliveryRoute=route;deliveryKey=key;
        nextStop=skipDeleted(route.optJSONArray("stops"),prefs().getInt("next_"+key,0));
        renderDeliveryRows(same?deliveryScroll.getScrollY():prefs().getInt("scroll_"+key,0));
        layoutDeliveryPanel();
    }
    private String displayAddress(String full){
        String[] parts=full.split(",");
        if(parts.length<2)return full;
        String street=parts[0].trim();
        String town=parts[1].trim().replaceFirst("(?i)\\s+MA(?:\\s+\\d{5}(?:-\\d{4})?)?$","").replaceFirst("\\s+\\d{5}(?:-\\d{4})?$","");
        return street+"\n"+town;
    }
    private void renderDeliveryRows(int scroll){
        if(deliveryRoute==null)return;
        JSONArray stops=moveOriginal!=null?moveStops:deliveryRoute.optJSONArray("stops");
        deliveryRows.removeAllViews();
        for(int i=0;i<stops.length();i++){
            final int index=i;JSONObject stop=stops.optJSONObject(i);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
            TextView number=new TextView(this);number.setText(String.valueOf(i+1));number.setTextColor(Color.WHITE);number.setBackgroundColor(moveOriginal!=null&&i==moveIndex?0xFF8545B5:stopColor(stop,0xFF34434F));number.setGravity(Gravity.CENTER);
            row.addView(number,new LinearLayout.LayoutParams(dp(36),-1));wireGrab(number);
            TextView address=new TextView(this);address.setText(displayAddress(stop.optString("address")));address.setTextColor(Color.BLACK);
            address.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);address.setTextSize(fontSp);
            address.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);address.setPadding(dp(6),dp(6),dp(8),dp(6));address.setMinHeight(dp(rowDp));
            address.setBackgroundColor(moveOriginal!=null&&i==moveIndex?0xFFE0C8F5:stop.optBoolean("deleted")?0xFFFCE1E1:moveOriginal==null&&i==nextStop?0xFF8DDC91:Color.WHITE);
            if(stop.optBoolean("deleted")){address.setTextColor(0xFFBC2222);address.setPaintFlags(address.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);}
            row.addView(address,new LinearLayout.LayoutParams(0,-2,1));
            address.setOnClickListener(v->selectDelivery(index,false));
            address.setOnLongClickListener(v->{deliveryOptions(index);return true;});
            deliveryRows.addView(row,new LinearLayout.LayoutParams(-1,-2));
            android.view.View line=new android.view.View(this);line.setBackgroundColor(0xFFCCCCCC);deliveryRows.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        }
        android.view.View end=new android.view.View(this);end.setBackgroundColor(0xFFC52C2C);deliveryRows.addView(end,new LinearLayout.LayoutParams(-1,dp(7)));
        deliveryScroll.post(()->deliveryScroll.scrollTo(0,scroll));
    }

    private final android.os.Handler navHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.ArrayList<android.view.View> floatingViews=new java.util.ArrayList<>();
    private String navAddress="";
    private boolean navActive=false, mapsPrepared=false, showingGoogle=false;
    private void launchDestination(JSONObject stop) throws Exception {
        navHandler.removeCallbacksAndMessages(null);
        navAddress=stop.getString("address").replaceFirst("\\s+\\d{5}(?:-\\d{4})?\\s*$","").trim();
        String lat=String.valueOf(stop.getDouble("lat")),lon=String.valueOf(stop.getDouble("lon"));
        android.content.Intent osm=new android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("osmand.api://navigate?dest_lat="+lat+"&dest_lon="+lon+"&dest_name="+android.net.Uri.encode(navAddress)+"&profile=car&force=1"));
        osm.setPackage("net.osmand");
        navActive=true;mapsPrepared=false;showingGoogle=false;
        if(android.provider.Settings.canDrawOverlays(this))showFloating();
        else Toast.makeText(this,"Enable floating controls in Navigation settings",Toast.LENGTH_LONG).show();
        if(prefs().getBoolean("prepare_google",true)){
            try{
                android.content.Intent g=new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("geo:0,0?q="+lat+","+lon));
                g.setPackage("com.google.android.apps.maps");startActivity(g);mapsPrepared=true;showingGoogle=true;
            }catch(Exception e){logDebug("Google launch "+e);}
        }
        Runnable go=()->{try{startActivity(osm);showingGoogle=false;}catch(Exception e){logDebug("OsmAnd launch "+e);Toast.makeText(this,"OsmAnd launch failed",Toast.LENGTH_LONG).show();}};
        if(mapsPrepared)navHandler.postDelayed(go,prefs().getInt("nav_delay",250));else go.run();
    }
    private void hideFloating(){
        android.view.WindowManager wm=(android.view.WindowManager)getSystemService(WINDOW_SERVICE);
        for(android.view.View v:floatingViews)try{wm.removeViewImmediate(v);}catch(Exception ignored){}
        floatingViews.clear();
    }
    private void showFloating(){
        hideFloating();
        if(!navActive||!android.provider.Settings.canDrawOverlays(this))return;
        if(prefs().getBoolean("float_list",true))addFloating("list","LIST",()->{
            android.content.Intent i=new android.content.Intent(this,MainActivity.class);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        if(prefs().getBoolean("float_address",true))addFloating("address",navAddress,()->{
            if(!mapsPrepared)return;
            String pkg=showingGoogle?"net.osmand":"com.google.android.apps.maps";
            android.content.Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
            if(i!=null)try{i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(i);showingGoogle=!showingGoogle;}catch(Exception e){logDebug("FOB toggle "+e);}
        });
    }
    private void cancelNavigation(){
        navHandler.removeCallbacksAndMessages(null);navActive=false;mapsPrepared=false;hideFloating();
        String result=NavigationStopListener.stopNavigation();logDebug("NAV STOP "+result);
        Toast.makeText(this,result,Toast.LENGTH_LONG).show();
    }
    private void addFloating(String key,String value,Runnable click){
        android.view.WindowManager wm=(android.view.WindowManager)getSystemService(WINDOW_SERVICE);
        TextView t=new TextView(this);t.setText(value);t.setTextColor(0xFFFF0000);t.setTypeface(null,android.graphics.Typeface.BOLD);
        t.setTextSize(prefs().getInt("float_size_"+key,20));t.setPadding(dp(8),dp(8),dp(8),dp(8));t.setMaxWidth(getResources().getDisplayMetrics().widthPixels-dp(16));
        android.view.WindowManager.LayoutParams p=new android.view.WindowManager.LayoutParams(-2,-2,android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT);
        t.measure(android.view.View.MeasureSpec.makeMeasureSpec(t.getMaxWidth(),android.view.View.MeasureSpec.AT_MOST),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));
        int width=getResources().getDisplayMetrics().widthPixels,height=getResources().getDisplayMetrics().heightPixels;
        int defaultX=Math.max(0,width-t.getMeasuredWidth()- (key.equals("list")?Math.round(width*0.055f):0));
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=prefs().getInt("float_x_"+key,defaultX);p.y=prefs().getInt("float_y_"+key,key.equals("list")?Math.round(height*0.19f):dp(4));
        t.setOnTouchListener(new android.view.View.OnTouchListener(){
            float x,y;int ox,oy;boolean moved,held;
            final Runnable hold=()->{if(!moved){held=true;cancelNavigation();}};
            public boolean onTouch(android.view.View v,android.view.MotionEvent e){
                switch(e.getActionMasked()){
                    case android.view.MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();ox=p.x;oy=p.y;moved=false;held=false;navHandler.postDelayed(hold,android.view.ViewConfiguration.getLongPressTimeout());return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if(held)return true;
                        if(Math.abs(e.getRawX()-x)+Math.abs(e.getRawY()-y)>dp(8)){moved=true;navHandler.removeCallbacks(hold);}
                        if(moved){p.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-t.getWidth(),ox+(int)(e.getRawX()-x)));p.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-t.getHeight(),oy+(int)(e.getRawY()-y)));wm.updateViewLayout(t,p);}return true;
                    case android.view.MotionEvent.ACTION_UP:
                        navHandler.removeCallbacks(hold);if(held)return true;
                        if(moved)prefs().edit().putInt("float_x_"+key,p.x).putInt("float_y_"+key,p.y).apply();else click.run();return true;
                    case android.view.MotionEvent.ACTION_CANCEL:navHandler.removeCallbacks(hold);return true;
                }return false;
            }
        });
        try{wm.addView(t,p);floatingViews.add(t);}catch(Exception e){logDebug("FOB "+e);}
    }
    private void showNavigationSettings(){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(16),dp(8),dp(16),dp(8));
        String[] keys={"native_nav","prepare_google","float_list","float_address"};
        String[] names={"Native navigation (off = Tasker)","Prepare Google Maps","Floating LIST","Floating address"};
        for(int n=0;n<keys.length;n++){String key=keys[n];android.widget.CheckBox b=new android.widget.CheckBox(this);b.setText(names[n]);b.setChecked(prefs().getBoolean(key,true));b.setOnCheckedChangeListener((v,on)->prefs().edit().putBoolean(key,on).apply());panel.addView(b);}
        TextView delay=new TextView(this);delay.setText("Google launch delay: "+prefs().getInt("nav_delay",250)+" ms");panel.addView(delay);
        android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(40);bar.setProgress(prefs().getInt("nav_delay",250)/50);panel.addView(bar);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(android.widget.SeekBar b,int p,boolean u){prefs().edit().putInt("nav_delay",p*50).apply();delay.setText("Google launch delay: "+p*50+" ms");}public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){}});
        for(String key:new String[]{"list","address"}){
            TextView label=new TextView(this);label.setText(key+" text size: "+prefs().getInt("float_size_"+key,20));panel.addView(label);
            android.widget.SeekBar size=new android.widget.SeekBar(this);size.setMax(36);size.setProgress(prefs().getInt("float_size_"+key,20)-12);panel.addView(size);
            size.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(android.widget.SeekBar b,int p,boolean u){prefs().edit().putInt("float_size_"+key,p+12).apply();label.setText(key+" text size: "+(p+12));}public void onStartTrackingTouch(android.widget.SeekBar b){}public void onStopTrackingTouch(android.widget.SeekBar b){}});
        }
        Button permission=new Button(this);permission.setText("Allow display over other apps");panel.addView(permission);permission.setOnClickListener(v->startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName()))));
        Button access=new Button(this);access.setText("Allow notification access (stop navigation)");panel.addView(access);access.setOnClickListener(v->startActivity(new android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(panel);
        new android.app.AlertDialog.Builder(this).setTitle("Navigation settings").setView(scroll).setPositiveButton("Done",null).show();
    }

    private JSONObject relocatingRoute;
    private int relocatingIndex=-1;
    private int stopColor(JSONObject s,int normal){
        return s.optBoolean("deleted")?0xFFC52C2C:s.optBoolean("added")?0xFFE88A19:s.optBoolean("edited")?0xFF8545B5:normal;
    }
    private int skipDeleted(JSONArray stops,int n){
        while(n<stops.length()&&stops.optJSONObject(n).optBoolean("deleted"))n++;
        return n;
    }
    private void editError(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    private JSONObject currentForEdit(JSONObject expected)throws Exception{
        JSONObject route=RouteInstaller.validate(new String(RouteInstaller.file(this,expected.getString("route")).readFully(),StandardCharsets.UTF_8));
        if(!route.toString().equals(expected.toString()))throw new Exception("Route changed; reopen delivery options");
        return route;
    }
    private void rememberEdit(JSONObject route)throws Exception{
        JSONArray stops=route.getJSONArray("stops");
        if(!route.has("office_stops")){
            JSONArray base=new JSONArray();
            for(int i=0;i<stops.length();i++)if(!stops.getJSONObject(i).optBoolean("added"))base.put(new JSONObject(stops.getJSONObject(i).toString()));
            route.put("office_stops",base);
        }
        JSONArray history=route.optJSONArray("edit_history");if(history==null)history=new JSONArray();
        JSONArray bounded=new JSONArray();
        for(int i=Math.max(0,history.length()-19);i<history.length();i++)bounded.put(history.get(i));
        bounded.put(new JSONObject().put("stops",new JSONArray(stops.toString())).put("next",nextStop));
        route.put("edit_history",bounded).put("original_legs",originalLegs(route));
    }
    private void saveEdited(JSONObject route,JSONArray stops,int next)throws Exception{
        if(stops.length()==0)throw new Exception("Keep at least one delivery; mark the last delivery deleted instead");
        JSONArray legs=new JSONArray();List<JSONObject> nodes=new ArrayList<>();
        JSONObject shop=route.optJSONObject("shop");if(shop!=null)nodes.add(shop);
        for(int i=0;i<stops.length();i++)nodes.add(stops.getJSONObject(i));if(shop!=null)nodes.add(shop);
        for(int i=0;i<nodes.size()-1;i++)legs.put(draftLeg(nodes.get(i),nodes.get(i+1)));
        route.put("stops",stops).put("legs",legs);
        String raw=route.toString();RouteInstaller.validate(raw);
        String slot=route.getString("route");android.util.AtomicFile file=RouteInstaller.file(this,slot);
        java.io.FileOutputStream out=file.startWrite();
        try{out.write(raw.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}
        String key=slot+"_"+route.optString("date","")+"_"+Integer.toHexString(stops.toString().hashCode());
        prefs().edit().putInt("next_"+key,skipDeleted(stops,Math.max(0,Math.min(next,stops.length())))).putInt("scroll_"+key,deliveryScroll.getScrollY()).apply();
        org.maplibre.android.camera.CameraPosition camera=readyMap.getCameraPosition();boolean rotate=rotationUnlocked;
        showSavedRoute(slot);rotationUnlocked=rotate;readyMap.getUiSettings().setRotateGesturesEnabled(rotate);
        readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(camera));updateCompass();saveView();
    }
    private void modifyStop(JSONObject expected,int index,String action,String address,LatLng point,int target){
        try{
            JSONObject route=currentForEdit(expected);rememberEdit(route);
            JSONArray old=route.getJSONArray("stops"),stops=new JSONArray();JSONObject s=old.getJSONObject(index);
            int next=nextStop;
            if(action.equals("delete"))s.put("deleted",true);
            if(action.equals("restore"))s.remove("deleted");
            if(action.equals("address"))s.put("address",address).put("edited",true);
            if(action.equals("location"))s.put("lat",point.getLatitude()).put("lon",point.getLongitude()).put("edited",true).put("relocated",true);
            if(action.equals("move"))s.put("edited",true);
            for(int i=0;i<old.length();i++)if(i!=index||(!action.equals("remove")&&!action.equals("move")))stops.put(old.get(i));
            if(action.equals("remove")&&index<next)next--;
            if(action.equals("move")){
                JSONArray moved=new JSONArray();for(int i=0;i<=stops.length();i++){if(i==target)moved.put(s);if(i<stops.length())moved.put(stops.get(i));}
                if(next==index)next=target;else{if(index<next)next--;if(target<=next)next++;}
                stops=moved;
            }
            saveEdited(route,stops,next);
        }catch(Exception e){editError(e);}
    }
    private JSONObject moveOriginal;
    private JSONArray moveStops;
    private int moveFrom=-1,moveIndex=-1,moveScroll;
    private LinearLayout moveBar;
    private Button moveUp,moveDown;
    private final android.os.Handler moveHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private void beginMove(int index){
        if(moveOriginal!=null||relocatingRoute!=null||draftOriginal!=null)return;
        try{
            moveOriginal=deliveryRoute;moveStops=new JSONArray(deliveryRoute.getJSONArray("stops").toString());
            moveFrom=moveIndex=index;moveScroll=deliveryScroll.getScrollY();
            panelCollapsed=false;layoutDeliveryPanel();hideControls();
            moveBar=new LinearLayout(this);moveBar.setBackgroundColor(0xFF34434F);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);lp.bottomMargin=dp(65);
            rootFrame.addView(moveBar,lp);
            moveUp=new Button(this);moveUp.setText("↑");moveUp.setTextSize(26);moveBar.addView(moveUp);wireMoveArrow(moveUp,-1);
            moveDown=new Button(this);moveDown.setText("↓");moveDown.setTextSize(26);moveBar.addView(moveDown);wireMoveArrow(moveDown,1);
            Button save=new Button(this);save.setText("Save");moveBar.addView(save);save.setOnClickListener(v->finishMove(true));
            Button cancel=new Button(this);cancel.setText("Cancel");moveBar.addView(cancel);cancel.setOnClickListener(v->finishMove(false));
            previewMove();
        }catch(Exception e){clearMove();editError(e);}
    }
    private void wireMoveArrow(Button b,int direction){
        b.setOnClickListener(v->stepMove(direction));
        b.setOnTouchListener(new android.view.View.OnTouchListener(){
            boolean repeated;
            final Runnable repeat=new Runnable(){public void run(){if(moveOriginal==null)return;repeated=true;stepMove(direction);if(moveIndex+direction>=0&&moveIndex+direction<moveStops.length())moveHandler.postDelayed(this,160);}};
            public boolean onTouch(android.view.View v,android.view.MotionEvent e){
                switch(e.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN:repeated=false;moveHandler.postDelayed(repeat,450);return false;
                case android.view.MotionEvent.ACTION_UP:moveHandler.removeCallbacks(repeat);return repeated;
                case android.view.MotionEvent.ACTION_CANCEL:moveHandler.removeCallbacks(repeat);return false;
                }return false;
            }
        });
    }
    private void stepMove(int direction){
        if(moveOriginal==null)return;
        int target=moveIndex+direction;if(target<0||target>=moveStops.length())return;
        try{Object a=moveStops.get(moveIndex),b=moveStops.get(target);moveStops.put(moveIndex,b);moveStops.put(target,a);moveIndex=target;previewMove();}catch(Exception e){editError(e);}
    }
    private void previewMove()throws Exception{
        renderDeliveryRows(deliveryScroll.getScrollY());
        final int selected=moveIndex;
        deliveryScroll.post(()->{if(moveOriginal==null||moveIndex!=selected)return;android.view.View row=deliveryRows.getChildAt(selected*2);if(row!=null){int y=deliveryScroll.getScrollY();if(row.getTop()<y)deliveryScroll.scrollTo(0,row.getTop());else if(row.getBottom()>y+deliveryScroll.getHeight())deliveryScroll.scrollTo(0,row.getBottom()-deliveryScroll.getHeight());}});
        updateMoveMarkers(moveStops,moveIndex);
        moveUp.setEnabled(moveIndex>0);moveDown.setEnabled(moveIndex<moveStops.length()-1);
    }
    private void updateMoveMarkers(JSONArray stops,int selected)throws Exception{
        if(readyStyle==null)return;
        GeoJsonSource source=readyStyle.getSourceAs("stops-source");if(source==null)return;
        List<Feature> markers=new ArrayList<>();
        for(int i=0;i<stops.length();i++){
            JSONObject s=stops.getJSONObject(i);double lat=s.getDouble("lat"),lon=s.getDouble("lon");
            int count=1;boolean later=false,highlight=i==selected;
            for(int j=0;j<stops.length();j++){if(j==i)continue;JSONObject other=stops.getJSONObject(j);if(lat==other.getDouble("lat")&&lon==other.getDouble("lon")){if(j<i)later=true;else count++;if(j==selected)highlight=true;}}
            if(later)continue;
            int color=highlight?0xFF8545B5:stopColor(s,0xFF1A6DB3);
            String icon="move_stop_"+(i+1)+"_"+count+"_"+color;
            readyStyle.addImage(icon,numberIcon(i+1,count,color));
            Feature f=Feature.fromGeometry(checkedPoint(lat,lon));f.addStringProperty("icon",icon);f.addStringProperty("address",s.getString("address"));f.addNumberProperty("number",i+1);markers.add(f);
        }
        JSONObject shop=deliveryRoute.optJSONObject("shop");if(shop!=null){Feature f=Feature.fromGeometry(checkedPoint(shop.getDouble("lat"),shop.getDouble("lon")));f.addStringProperty("icon","shop_marker");markers.add(f);}
        source.setGeoJson(FeatureCollection.fromFeatures(markers));
    }
    private void clearMove(){
        moveHandler.removeCallbacksAndMessages(null);
        if(moveBar!=null){rootFrame.removeView(moveBar);moveBar=null;}
        moveOriginal=null;moveStops=null;moveFrom=moveIndex=-1;
    }
    private void finishMove(boolean save){
        if(moveOriginal==null)return;
        JSONObject expected=moveOriginal;int from=moveFrom,to=moveIndex,scroll=moveScroll;
        clearMove();
        try{updateMoveMarkers(expected.getJSONArray("stops"),-1);renderDeliveryRows(scroll);}catch(Exception e){editError(e);}
        if(save&&from!=to)modifyStop(expected,from,"move",null,null,to);
    }

    private void deliveryOptions(int index){
        if(moveOriginal!=null)return;
        JSONObject expected=deliveryRoute;if(expected==null)return;
        JSONObject s=expected.optJSONArray("stops").optJSONObject(index);if(s==null)return;
        String[] options={"Navigate","Edit address","Change marker location","Move delivery in list",s.optBoolean("deleted")?"Restore delivery":"Remove delivery","Satellite view","Street view","Tasker options"};
        new android.app.AlertDialog.Builder(this).setTitle((index+1)+" — "+s.optString("address")).setItems(options,(d,w)->{
            if(expected!=deliveryRoute)return;
            if(w==0){selectDelivery(index,false);return;}
            if(w==1){
                android.widget.EditText input=new android.widget.EditText(this);input.setText(s.optString("address"));
                new android.app.AlertDialog.Builder(this).setTitle("Edit address").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",(a,b)->{String name=input.getText().toString().trim();if(!name.isEmpty())modifyStop(expected,index,"address",name,null,0);}).show();
            }else if(w==2){
                cancelDraft();relocatingRoute=expected;relocatingIndex=index;
                draftResume=new Button(this);draftResume.setText("Cancel marker move");FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);lp.bottomMargin=dp(65);rootFrame.addView(draftResume,lp);
                draftResume.setOnClickListener(v->{relocatingRoute=null;relocatingIndex=-1;cancelDraft();});
                Toast.makeText(this,"Long press the new marker location",Toast.LENGTH_LONG).show();
            }else if(w==3){
                beginMove(index);
            }else if(w==4){
                if(s.optBoolean("deleted"))modifyStop(expected,index,"restore",null,null,0);
                else new android.app.AlertDialog.Builder(this).setTitle("Remove delivery?").setItems(new String[]{"Permanently Remove","Leave and Mark Deleted"},(a,b)->{
                    if(b==0)new android.app.AlertDialog.Builder(this).setMessage("Permanently remove this stop from the list?").setNegativeButton("Cancel",null).setPositiveButton("Remove",(x,y)->modifyStop(expected,index,"remove",null,null,0)).show();
                    else modifyStop(expected,index,"delete",null,null,0);
                }).setNegativeButton("Cancel",null).show();
            }else if(w==5||w==6){
                String coord=s.optDouble("lat")+","+s.optDouble("lon");
                String uri=w==5?"https://www.google.com/maps/@?api=1&map_action=map&center="+coord+"&zoom=19&basemap=satellite":"https://www.google.com/maps/@?api=1&map_action=pano&viewpoint="+coord;
                try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(uri)).setPackage("com.google.android.apps.maps"));}catch(Exception e){editError(e);}
            }else selectDelivery(index,true);
        }).setNegativeButton("Cancel",null).show();
    }
    private void routeEditMenu(){
        if(moveOriginal!=null)return;
        if(deliveryRoute==null)return;JSONObject expected=deliveryRoute;
        new android.app.AlertDialog.Builder(this).setTitle("Route changes").setItems(new String[]{"Undo last change","Restore original office route"},(d,w)->{
            if(w==0)restoreEdit(expected,false);
            else new android.app.AlertDialog.Builder(this).setMessage("Discard all driver changes to this route?").setNegativeButton("Cancel",null).setPositiveButton("Restore",(a,b)->restoreEdit(expected,true)).show();
        }).setNegativeButton("Cancel",null).show();
    }
    private void restoreEdit(JSONObject expected,boolean all){
        try{
            JSONObject route=currentForEdit(expected);JSONArray history=route.optJSONArray("edit_history");
            if(all){
                JSONArray base=route.optJSONArray("office_stops");if(base==null){if(!route.has("original_legs"))throw new Exception("Already original");rememberEdit(route);base=route.getJSONArray("office_stops");}else rememberEdit(route);
                saveEdited(route,new JSONArray(base.toString()),0);
            }else{
                if(history==null||history.length()==0)throw new Exception("No saved undo history");
                JSONObject last=history.getJSONObject(history.length()-1);history.remove(history.length()-1);
                saveEdited(route,last.getJSONArray("stops"),last.optInt("next",0));
            }
        }catch(Exception e){editError(e);}
    }
    private boolean handleMapLongPress(LatLng point){
        if(moveOriginal!=null)return true;
        if(relocatingRoute!=null){
            JSONObject expected=relocatingRoute;int index=relocatingIndex;relocatingRoute=null;relocatingIndex=-1;cancelDraft();
            if(expected==deliveryRoute)new android.app.AlertDialog.Builder(this).setMessage("Move marker to selected location?").setNegativeButton("Cancel",null).setPositiveButton("Move",(d,w)->modifyStop(expected,index,"location",null,point,0)).show();
            return true;
        }
        if(draftOriginal==null&&deliveryRoute!=null&&readyStyle!=null&&readyStyle.getLayer("numbered-stops")!=null){
            android.graphics.PointF p=readyMap.getProjection().toScreenLocation(point);
            List<Feature> hits=readyMap.queryRenderedFeatures(p,"numbered-stops");
            for(Feature f:hits)if(f.hasProperty("number")){
                int index=f.getNumberProperty("number").intValue()-1;JSONArray stops=deliveryRoute.optJSONArray("stops");JSONObject s=stops.optJSONObject(index);if(s==null)continue;
                List<Integer> ids=new ArrayList<>();List<String> names=new ArrayList<>();
                for(int i=0;i<stops.length();i++){JSONObject other=stops.optJSONObject(i);if(other.optDouble("lat")==s.optDouble("lat")&&other.optDouble("lon")==s.optDouble("lon")){ids.add(i);names.add((i+1)+" — "+other.optString("address"));}}
                if(ids.size()==1)deliveryOptions(index);else new android.app.AlertDialog.Builder(this).setTitle("Select delivery").setItems(names.toArray(new String[0]),(d,w)->deliveryOptions(ids.get(w))).show();
                return true;
            }
        }
        beginAddDelivery(point);return true;
    }

    private void selectDelivery(int index,boolean longPress){
        if(moveOriginal!=null)return;
        try{
            JSONArray stops=deliveryRoute.getJSONArray("stops");JSONObject s=stops.getJSONObject(index);
            android.content.Intent intent=new android.content.Intent("com.routeviewer.maplibre."+(longPress?"LIST_LONG_PRESS":"LIST_TAP"));
            intent.setPackage("net.dinglisch.android.taskerm");
            intent.putExtra("address",s.getString("address"));intent.putExtra("lat",String.valueOf(s.getDouble("lat")));intent.putExtra("lon",String.valueOf(s.getDouble("lon")));
            intent.putExtra("index",String.valueOf(index+1));intent.putExtra("route",deliveryRoute.getString("route"));intent.putExtra("date",deliveryRoute.optString("date",""));intent.putExtra("total",String.valueOf(stops.length()));
            if(!longPress){
                nextStop=skipDeleted(stops,index+1);int y=deliveryRows.getChildAt(index*2).getTop();
                prefs().edit().putInt("next_"+deliveryKey,nextStop).putInt("scroll_"+deliveryKey,y).apply();
                for(int i=0;i<stops.length();i++)((LinearLayout)deliveryRows.getChildAt(i*2)).getChildAt(1).setBackgroundColor(stops.optJSONObject(i).optBoolean("deleted")?0xFFFCE1E1:i==nextStop?0xFF8DDC91:Color.WHITE);
                deliveryScroll.scrollTo(0,y);
            }
            if(longPress||!prefs().getBoolean("native_nav",true))sendBroadcast(intent);else launchDestination(s);logDebug("LIST "+(longPress?"long":"tap")+" "+(index+1));
        }catch(Exception e){logDebug("LIST error "+e);Toast.makeText(this,"Selection failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }

    private TextView status, offlineStatus, mileage;
    private Button compass;
    private boolean rotationUnlocked=false;
    private Button downloadButton, showRouteButton, showRegionButton, diagnosticButton;
    private Spinner regionPicker;
    private final java.util.HashMap<String, OfflineRegion> savedRegions = new java.util.HashMap<>();
    private int activeRegionIndex = -1;
    private OfflineRegion offlineRegion;
    private boolean downloading;
    private int markerPercent=75, retryCount=0;
    private long retryProgress=-1;
    private boolean retryPending=false;
    private final android.os.Handler retryHandler=new android.os.Handler(android.os.Looper.getMainLooper());
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
    private LinearLayout controls, maintenance;
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
        markerPercent=Math.max(20,Math.min(200,getPreferences(MODE_PRIVATE).getInt("marker_percent",75)));
        registerReceiver(routeChanged,new android.content.IntentFilter("com.routeviewer.maplibre.ROUTE_CHANGED"));
        MapLibre.getInstance(this);
        org.maplibre.android.log.Logger.setVerbosity(android.util.Log.VERBOSE);
        logDebug("START v1.4.18 "+networkState());

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
        status.setText("MapLibre Route Viewer v1.4.18 • AM / PM / EX");
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0xBB182A36);
        status.setPadding(18, 12, 18, 12);
        LinearLayout header=new LinearLayout(this);
        header.setBackgroundColor(0xBB182A36);
        header.setGravity(Gravity.CENTER_VERTICAL);
        status.setBackgroundColor(Color.TRANSPARENT);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.LEFT);
        header.addView(status,new LinearLayout.LayoutParams(-2,-2));
        mileage=new TextView(this); mileage.setTextColor(Color.WHITE); mileage.setGravity(Gravity.LEFT); mileage.setPadding(12,12,18,12);
        header.addView(mileage,new LinearLayout.LayoutParams(-2,-2));
        frame.addView(header,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
        compass=new Button(this);
        compass.setAllCaps(false);
        compass.setText("↑ N");
        compass.setContentDescription("North up locked. Long press to unlock rotation.");
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);
        frame.addView(compass,cp);
        header.addOnLayoutChangeListener((v,l,t,rr,b,ol,ot,orr,ob)->{if(cp.topMargin!=b-t){cp.topMargin=b-t;compass.setLayoutParams(cp);}});
        compass.setOnClickListener(v->lockNorth());
        compass.setOnLongClickListener(v->{if(readyMap==null)return true;rotationUnlocked=true;readyMap.getUiSettings().setRotateGesturesEnabled(true);updateCompass();Toast.makeText(this,"Rotation unlocked. Tap compass to lock north up.",Toast.LENGTH_SHORT).show();return true;});

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xDD182A36);
        regionPicker = new Spinner(this);
        ArrayAdapter<String> regionAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, REGION_NAMES);
        regionPicker.setAdapter(regionAdapter);
        maintenance = new LinearLayout(this);
        maintenance.setOrientation(LinearLayout.VERTICAL);
        maintenance.setVisibility(android.view.View.GONE);
        maintenance.addView(regionPicker);
        showRouteButton = new Button(this);
        showRouteButton.setText("Show demo route (use AFTER disconnecting)");
        showRouteButton.setEnabled(false);
        // Demo route is no longer exposed in the UI.
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
        Button editRoute=new Button(this);editRoute.setText("Undo / Restore route");controls.addView(editRoute);editRoute.setOnClickListener(v->routeEditMenu());
        showRegionButton = new Button(this);
        showRegionButton.setText("Show selected region (AFTER disconnecting)");
        showRegionButton.setEnabled(false);
        maintenance.addView(showRegionButton);
        diagnosticButton = new Button(this);
        diagnosticButton.setText("HOUSE NUMBER DIAGNOSTICS (CURRENT VIEW)");
        diagnosticButton.setEnabled(false);
        maintenance.addView(diagnosticButton);
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
        maintenance.addView(downloadButton);
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
        for(String name:new String[]{"Reload Map","Maintenance","Hide"}) {
            Button b=new Button(this); b.setText(name);
            tools.addView(b,new LinearLayout.LayoutParams(0,-2,1));
            b.setOnClickListener(v -> {
                if(name.equals("Reload Map")) reloadMap();
                else if(name.equals("Maintenance")) maintenance.setVisibility(maintenance.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE);
                else hideControls();
            });
        }
        controls.addView(tools);
        Button settings=new Button(this); settings.setText("Settings");
        settings.setOnClickListener(v -> showMarkerSettings()); controls.addView(settings);
        Button exportButton=new Button(this); exportButton.setText("Export Debug Log / Database Diagnostics");
        exportButton.setOnClickListener(v -> exportDebug()); maintenance.addView(exportButton);
        controls.addView(maintenance);
        controls.addView(offlineStatus);
        regionPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                    android.view.View view, int position, long id) {
                if (!downloading) refreshSelectedRegion();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        frame.addView(controls, new FrameLayout.LayoutParams(Math.min(dp(440),getResources().getDisplayMetrics().widthPixels), -2, Gravity.BOTTOM|Gravity.LEFT));
        LinearLayout floating=new LinearLayout(this);
        menuButton=new Button(this); menuButton.setText("Menu");
        menuButton.setOnClickListener(v -> {if(moveOriginal!=null)return;controls.setVisibility(android.view.View.VISIBLE); floating.setVisibility(android.view.View.GONE);});
        floating.addView(menuButton);
        Button fit=new Button(this); fit.setText("Fit Route");
        fit.setOnClickListener(v -> {if(readyMap!=null && routeBounds!=null) try {lockNorth();readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(routeBounds,90));} catch(Exception e){logDebug("Fit error "+e);}});
        floating.addView(fit);
        frame.addView(floating,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT));
        floating.setVisibility(android.view.View.GONE);
        menuButton.setTag(floating);
        createDeliveryPanel(frame);
        controls.bringToFront();floating.bringToFront();
        restoringView=prefs().contains("view_lat");
        String savedSlot=prefs().getString("view_slot","");
        if(java.util.Arrays.asList(routeSlots).contains(savedSlot))pendingSlot=savedSlot;
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
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setCompassEnabled(false);
            map.addOnCameraMoveListener(() -> updateCompass());
            map.addOnCameraIdleListener(() -> {updateHouseDensity();saveView();});
            map.setMaxPitchPreference(0.0);
            readyMap=map;
            map.addOnMapLongClickListener(point -> handleMapLongPress(point));
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
                originalRoadFilters.clear();
                originalStreetSizes.clear();
                readyStyle = style;
                updateHouseDensity();
                showRouteButton.setEnabled(true);
                showRegionButton.setEnabled(true);
                diagnosticButton.setEnabled(true);
                status.setText("v1.4.18 • Import route JSON or select AM / PM / EX");
                logDebug("STYLE ready sources="+style.getSources().size());
                if (pendingSlot != null) showSavedRoute(pendingSlot);
                else if(demoRouteShown) try {loadRoute(style,map);} catch(Exception e){logDebug("Demo restore "+e);}
                if(reloadCamera!=null) map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(reloadCamera));
                reloadCamera=null; reloading=false;
                if(restoringView){restoreView();restoringView=false;hideControls();saveView();}
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

    private void applyMarkerSize(int percent) {
        markerPercent=percent;
        getPreferences(MODE_PRIVATE).edit().putInt("marker_percent",percent).apply();
        if(readyStyle!=null) {
            Layer layer=readyStyle.getLayer("numbered-stops");
            if(layer instanceof SymbolLayer) layer.setProperties(PropertyFactory.iconSize(percent/100f));
        }
    }
    private void showMarkerSettings() {
        LinearLayout panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24,16,24,16);
        TextView label=new TextView(this); label.setText("Marker size: "+markerPercent+"%");
        panel.addView(label);
        android.widget.SeekBar slider=new android.widget.SeekBar(this);
        slider.setMax(36); slider.setProgress((markerPercent-20)/5);
        LinearLayout sizing=new LinearLayout(this);
        Button minus=new Button(this);minus.setText("−");minus.setOnClickListener(v->slider.setProgress(slider.getProgress()-1));
        Button plus=new Button(this);plus.setText("+");plus.setOnClickListener(v->slider.setProgress(slider.getProgress()+1));
        sizing.addView(minus,new LinearLayout.LayoutParams(dp(48),-2));sizing.addView(slider,new LinearLayout.LayoutParams(0,-2,1));sizing.addView(plus,new LinearLayout.LayoutParams(dp(48),-2));panel.addView(sizing);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){
                int percent=20+progress*5; label.setText("Marker size: "+percent+"%"); applyMarkerSize(percent);
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar){}
            public void onStopTrackingTouch(android.widget.SeekBar bar){}
        });
        Button reset=new Button(this); reset.setText("Reset to 75%"); panel.addView(reset);
        reset.setOnClickListener(v->slider.setProgress(11));
        for(boolean font:new boolean[]{true,false}){
            TextView title=new TextView(this);title.setText(font?"Address font: "+fontSp+" sp":"Row height: "+rowDp+" dp");panel.addView(title);
            android.widget.SeekBar bar=new android.widget.SeekBar(this);bar.setMax(font?18:64);bar.setProgress(font?fontSp-12:rowDp-36);panel.addView(bar);
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(android.widget.SeekBar b,int p,boolean user){
                    if(font)fontSp=12+p;else rowDp=36+p;
                    title.setText(font?"Address font: "+fontSp+" sp":"Row height: "+rowDp+" dp");
                    prefs().edit().putInt("font_sp",fontSp).putInt("row_dp",rowDp).apply();
                    renderDeliveryRows(deliveryScroll.getScrollY());
                }
                public void onStartTrackingTouch(android.widget.SeekBar b){}
                public void onStopTrackingTouch(android.widget.SeekBar b){}
            });
        }
        Button resetList=new Button(this);resetList.setText("Reset list position");panel.addView(resetList);
        resetList.setOnClickListener(v->{nextStop=0;prefs().edit().putInt("next_"+deliveryKey,0).putInt("scroll_"+deliveryKey,0).apply();renderDeliveryRows(0);});
        Button navSettings=new Button(this);navSettings.setText("Navigation settings");panel.addView(navSettings);navSettings.setOnClickListener(v->showNavigationSettings());
        new android.app.AlertDialog.Builder(this).setTitle("Display settings").setView(panel).setPositiveButton("Done",null).show();
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
        retryCount=0; retryProgress=-1; retryPending=false;
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
        retryHandler.removeCallbacksAndMessages(null); retryPending=false;
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
        offlineRegion=region;
        region.setObserver(new OfflineRegion.OfflineRegionObserver() {
            @Override public void onStatusChanged(OfflineRegionStatus st) {
                if (activeRegionIndex != index || retryPending || isDestroyed()) return;
                if(st.getCompletedResourceCount()>retryProgress){retryProgress=st.getCompletedResourceCount(); retryCount=0;}
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
                if(activeRegionIndex!=index || retryPending || isDestroyed()) return;
                String reason=String.valueOf(error.getReason());
                String detail=error.getMessage();
                String lower=(reason+" "+detail).toLowerCase(Locale.ROOT);
                boolean transientError=lower.contains("connection") || lower.contains("server") || lower.contains("resolve host") || lower.contains("timeout") || lower.contains("network");
                if(!transientError || retryCount>=5){
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                    finishWithError("Download error: "+detail); return;
                }
                retryPending=true;
                int attempt=++retryCount;
                long delay=attempt==1?10000L:attempt==2?30000L:60000L;
                region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                logDebug("DOWNLOAD retry "+attempt+" in "+delay+"ms: "+detail);
                offlineStatus.setText(REGION_NAMES[index]+": retry "+attempt+"/5 in "+(delay/1000)+" sec. Saved tiles retained.");
                retryHandler.postDelayed(()->{
                    if(isDestroyed() || activeRegionIndex!=index || !downloading) return;
                    retryPending=false;
                    if(getFilesDir().getUsableSpace()<256L*1024*1024){finishWithError("Download stopped: low storage");return;}
                    offlineStatus.setText("Resuming "+REGION_NAMES[index]+"...");
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                },delay);
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
            PropertyFactory.iconSize(markerPercent/100f)
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
            addStyledRouteLine(style,"here-route-line",java.util.Collections.singletonList(Feature.fromGeometry(LineString.fromLngLats(coords))));
        }
        routeBounds=bounds.build();
        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory
            .newLatLngBounds(routeBounds, 90));
        status.setText(stops.length() + " numbered stops • overlap enabled • demo route");
    }


    private final java.util.Map<String,Expression> originalStreetSizes=new java.util.HashMap<>();
    private void addStyledRouteLine(Style style,String id,List<Feature> routeLines) {
        // Use one native street label layer set for all roads.
        String anchor=null;
        int labelCount=0;
        for(Layer candidate:style.getLayers()){
            if(!(candidate instanceof SymbolLayer))continue;
            SymbolLayer label=(SymbolLayer)candidate;
            if(!"transportation_name".equals(label.getSourceLayer()))continue;
            if(anchor==null)anchor=label.getId();
            // Scale the original size, preserving zoom-dependent expressions.
            // Cache the original property so repeated route selections do not compound the increase.
            if(!originalStreetSizes.containsKey(label.getId())){
                org.maplibre.android.style.layers.PropertyValue<Float> size=label.getTextSize();
                if(size.isExpression()) originalStreetSizes.put(label.getId(),size.getExpression());
                else if(size.isValue()) originalStreetSizes.put(label.getId(),Expression.literal(size.getValue()));
            }
            Expression originalSize=originalStreetSizes.get(label.getId());
            if(originalSize!=null){
                // A top-level zoom interpolation must remain top-level in MapLibre.
                try {
                    Object parsed=new org.json.JSONTokener(originalSize.toString()).nextValue();
                    if(parsed instanceof Number){label.setProperties(PropertyFactory.textSize(((Number)parsed).floatValue()*1.1f));}
                    JSONArray expr=parsed instanceof JSONArray?(JSONArray)parsed:new JSONArray();
                    String op=expr.optString(0);
                    if("interpolate".equals(op)||"step".equals(op)){
                        int start="interpolate".equals(op)?4:2;
                        for(int k=start;k<expr.length();k+=2){
                            Object value=expr.get(k);
                            if(value instanceof Number)expr.put(k,((Number)value).doubleValue()*1.1);
                            else expr.put(k,new JSONArray().put("*").put(value).put(1.1));
                        }
                        label.setProperties(PropertyFactory.textSize(Expression.Converter.convert(expr.toString())));
                    }else if(!(parsed instanceof Number)) label.setProperties(PropertyFactory.textSize(Expression.product(originalSize,Expression.literal(1.1))));
                }catch(Exception e){logDebug("Street size unchanged "+label.getId()+" "+e);}
            }
            label.setProperties(PropertyFactory.textColor("#000000"),
                PropertyFactory.textHaloColor(android.graphics.Color.TRANSPARENT),
                PropertyFactory.textHaloWidth(0.0f),
                PropertyFactory.textLetterSpacing(0.08f),
                PropertyFactory.textHaloBlur(0.0f));
            labelCount++;
        }
        LineLayer border=new LineLayer("route-line-border","route-source");
        border.withProperties(PropertyFactory.lineColor("#303BB5"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(),Expression.zoom(),Expression.stop(9,4f),Expression.stop(13,6f),Expression.stop(17,12f))),
            PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        LineLayer line=new LineLayer(id,"route-source");
        line.withProperties(PropertyFactory.lineColor("#6976EF"),
            PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(),Expression.zoom(),Expression.stop(9,2.5f),Expression.stop(13,4f),Expression.stop(17,9f))),
            PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"));
        if(anchor==null && style.getLayer("delivery-house-numbers")!=null)anchor="delivery-house-numbers";
        if(anchor==null && style.getLayer("numbered-stops")!=null)anchor="numbered-stops";
        if(anchor!=null){style.addLayerBelow(border,anchor);style.addLayerBelow(line,anchor);}
        else{style.addLayer(border);style.addLayer(line);}
        logDebug("ROUTE STYLE anchor="+anchor+" road labels="+labelCount);
        // No separate route street labels or suppression filters.
    }

    private List<Feature> activeLabelRoute;
    private final java.util.Map<String,Expression> originalRoadFilters=new java.util.HashMap<>();
    private static final double LABEL_SX=111320.0*Math.cos(Math.toRadians(42.5)), LABEL_SY=111320.0;
    private org.locationtech.jts.geom.LineString labelLine(org.locationtech.jts.geom.GeometryFactory gf,List<Point> pts){
        org.locationtech.jts.geom.Coordinate[] cs=new org.locationtech.jts.geom.Coordinate[pts.size()];
        for(int i=0;i<pts.size();i++)cs[i]=new org.locationtech.jts.geom.Coordinate(pts.get(i).longitude()*LABEL_SX,pts.get(i).latitude()*LABEL_SY);
        return gf.createLineString(cs);
    }
    private void refreshRouteLabels(){
        if(readyStyle==null||readyMap==null||activeLabelRoute==null)return;
        try{
            Style style=readyStyle;
            if(!(style.getSource("openmaptiles") instanceof VectorSource))return;
            org.locationtech.jts.geom.GeometryFactory gf=new org.locationtech.jts.geom.GeometryFactory();
            List<org.locationtech.jts.geom.Geometry> route=new ArrayList<>();
            for(Feature f:activeLabelRoute)if(f.geometry() instanceof LineString){
                List<Point> pts=((LineString)f.geometry()).coordinates();
                if(pts.size()>1)route.add(labelLine(gf,pts));
            }
            if(route.isEmpty())return;
            org.locationtech.jts.geom.Geometry corridor=gf.buildGeometry(route).buffer(12,4);
            List<Feature> roads=((VectorSource)style.getSource("openmaptiles")).querySourceFeatures(new String[]{"transportation_name"},null);
            List<Feature> labels=new ArrayList<>();
            java.util.Set<String> seen=new java.util.HashSet<>();
            java.util.Set<String> matchedNames=new java.util.HashSet<>();
            int skipped=0,processed=0,multi=0;
            for(Feature road:roads){
                try {
                if(!road.hasProperty("name"))continue;
                String name=road.getStringProperty("name");
                if(name==null||name.isEmpty())continue;
                List<LineString> lines=new ArrayList<>();
                if(road.geometry() instanceof LineString)lines.add((LineString)road.geometry());
                else if(road.geometry() instanceof org.maplibre.geojson.MultiLineString){
                    multi++;
                    for(List<Point> points:((org.maplibre.geojson.MultiLineString)road.geometry()).coordinates())lines.add(LineString.fromLngLats(points));
                }else{skipped++;continue;}
                processed++;
                for(LineString segment:lines){
                try {
                List<Point> pts=segment.coordinates();
                if(pts.size()<2)continue;
                org.locationtech.jts.geom.LineString full=labelLine(gf,pts);
                if(!full.getEnvelopeInternal().intersects(corridor.getEnvelopeInternal()))continue;
                org.locationtech.jts.geom.Geometry overlap=full.intersection(corridor);
                for(int i=0;i<overlap.getNumGeometries();i++){
                    org.locationtech.jts.geom.Geometry part=overlap.getGeometryN(i);
                    if(!(part instanceof org.locationtech.jts.geom.LineString)||part.getLength()<35)continue;
                    List<Point> coords=new ArrayList<>();
                    for(org.locationtech.jts.geom.Coordinate q:part.getCoordinates())coords.add(Point.fromLngLat(q.x/LABEL_SX,q.y/LABEL_SY));
                    String key=name+coords.toString();
                    if(!seen.add(key))continue;
                    Feature f=Feature.fromGeometry(LineString.fromLngLats(coords));
                    f.addStringProperty("name",name);labels.add(f);matchedNames.add(name);
                    if(labels.size()<=40)logDebug("LABEL MATCH "+name+" overlap="+Math.round(part.getLength())+"m total="+Math.round(full.getLength())+"m");
                }
                }catch(Exception e){skipped++;if(skipped<=5)logDebug("LABEL SEGMENT SKIP "+e);}
                }
                }catch(Exception e){skipped++;if(skipped<=5)logDebug("LABEL FEATURE SKIP "+e);}
            }
            GeoJsonSource source=(GeoJsonSource)style.getSource("route-street-source");
            if(source==null){
                source=new GeoJsonSource("route-street-source",FeatureCollection.fromFeatures(labels));style.addSource(source);
                SymbolLayer layer=new SymbolLayer("route-street-labels","route-street-source");
                layer.withProperties(PropertyFactory.symbolPlacement("line"),PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(new String[]{"Noto Sans Regular"}),PropertyFactory.textSize(14f),
                    PropertyFactory.textColor("#FFFFFF"),PropertyFactory.textHaloColor("#30376A"),
                    PropertyFactory.textHaloWidth(0.7f),PropertyFactory.textHaloBlur(0.25f),PropertyFactory.textLetterSpacing(0.08f),
                    PropertyFactory.textAllowOverlap(false),PropertyFactory.textIgnorePlacement(false),
                    PropertyFactory.textPadding(6f),PropertyFactory.symbolSpacing(300f));
                layer.setMinZoom(13f);
                style.addLayer(layer);
            }else source.setGeoJson(FeatureCollection.fromFeatures(labels));
            // Hide black labels only on features entirely within the route corridor.
            // Partial features remain black outside the route; duplicate labels are possible.
            List<Expression> inside=new ArrayList<>();
            for(int i=0;i<corridor.getNumGeometries();i++){
                org.locationtech.jts.geom.Geometry g=corridor.getGeometryN(i);
                if(!(g instanceof org.locationtech.jts.geom.Polygon))continue;
                org.locationtech.jts.geom.Polygon poly=(org.locationtech.jts.geom.Polygon)g;
                List<List<Point>> rings=new ArrayList<>();
                for(int k=-1;k<poly.getNumInteriorRing();k++){
                    List<Point> ring=new ArrayList<>();
                    for(org.locationtech.jts.geom.Coordinate q:(k<0?poly.getExteriorRing():poly.getInteriorRingN(k)).getCoordinates())ring.add(Point.fromLngLat(q.x/LABEL_SX,q.y/LABEL_SY));
                    rings.add(ring);
                }
                inside.add(Expression.within(org.maplibre.geojson.Polygon.fromLngLats(rings)));
            }
            Expression outside=inside.isEmpty()?Expression.literal(true):Expression.all(inside.stream().map(e -> Expression.not(e)).toArray(Expression[]::new));
            int filterLayers=0;
            for(Layer l:style.getLayers())if(l instanceof SymbolLayer && "transportation_name".equals(((SymbolLayer)l).getSourceLayer())){
                SymbolLayer sl=(SymbolLayer)l;
                sl.setProperties(PropertyFactory.textAllowOverlap(false),PropertyFactory.textIgnorePlacement(false));
                if(!originalRoadFilters.containsKey(sl.getId()))originalRoadFilters.put(sl.getId(),sl.getFilter());
                Expression original=originalRoadFilters.get(sl.getId());
                sl.setFilter(original==null?outside:Expression.all(original,outside));
                filterLayers++;
            }
            logDebug("LABEL SUMMARY roads="+roads.size()+" matched="+labels.size()+" names="+matchedNames.size()+" processed="+processed+" multi="+multi+" skipped="+skipped+" suppressionLayers="+filterLayers+" corridorPolygons="+inside.size());
        }catch(Exception e){logDebug("LABEL MATCH ERROR "+e);}
    }

    private void clearRouteLayers(Style style) {
        activeLabelRoute=null;
        for(java.util.Map.Entry<String,Expression> e:originalRoadFilters.entrySet()){
            Layer l=style.getLayer(e.getKey());
            if(l instanceof SymbolLayer)((SymbolLayer)l).setFilter(e.getValue()==null?Expression.literal(true):e.getValue());
        }
        originalRoadFilters.clear();
        if(style.getLayer("route-street-labels")!=null)style.removeLayer("route-street-labels");
        if(style.getSource("route-street-source")!=null)style.removeSource("route-street-source");
        for (String id : new String[]{"numbered-stops","here-route-line","tomtom-route-line","route-line-border","unrouted-line"}) {
            if (style.getLayer(id) != null) style.removeLayer(id);
        }
        for (String id : new String[]{"stops-source","route-source","unrouted-source"}) {
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
        if (!"tomtom".equals(route.getString("provider")) && !"here".equals(route.getString("provider"))) throw new Exception("Expected TomTom or HERE provider");
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

    private void updateCompass(){
        if(compass==null || readyMap==null)return;
        int bearing=(int)Math.round((readyMap.getCameraPosition().bearing+360)%360)%360;
        compass.setText(rotationUnlocked?"N "+bearing+"°":"↑ N");
        compass.setContentDescription(rotationUnlocked?"Rotation unlocked. Tap to lock north up.":"North up locked. Long press to unlock rotation.");
    }
    private void lockNorth(){
        rotationUnlocked=false;
        if(readyMap!=null){readyMap.getUiSettings().setRotateGesturesEnabled(false);readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(new org.maplibre.android.camera.CameraPosition.Builder(readyMap.getCameraPosition()).bearing(0).build()));}
        updateCompass();
    }
    private void updateHouseDensity(){
        if(readyStyle==null || readyMap==null)return;
        Layer layer=readyStyle.getLayer("delivery-house-numbers");
        if(layer instanceof SymbolLayer){double z=readyMap.getCameraPosition().zoom; layer.setProperties(PropertyFactory.textAllowOverlap(z>=17),PropertyFactory.textPadding(z<16?2f:0f));}
    }
    private void clearDraftMarker(){
        if(readyStyle==null)return;
        if(readyStyle.getLayer("draft-delivery")!=null)readyStyle.removeLayer("draft-delivery");
        if(readyStyle.getSource("draft-delivery-source")!=null)readyStyle.removeSource("draft-delivery-source");
    }
    private android.app.AlertDialog draftDialog;
    private Button draftResume;
    private LatLng draftPoint;
    private JSONObject draftOriginal;
    private String draftSlot, draftAddress="";
    private int draftIndex;
    private double[] nearestLeg(JSONArray legs, double lat,double lon) throws Exception {
        double best=Double.POSITIVE_INFINITY,second=best,blat=lat,blon=lon;int leg=-1;
        double scale=Math.cos(Math.toRadians(lat));
        for(int i=0;i<legs.length();i++){
            JSONObject l=legs.getJSONObject(i);if(l.optBoolean("unrouted"))continue;
            JSONArray pts=l.getJSONArray("points");double local=Double.POSITIVE_INFINITY,la=lat,lo=lon;
            for(int j=1;j<pts.length();j++){
                JSONObject p=pts.getJSONObject(j-1),q=pts.getJSONObject(j);
                double x=(p.getDouble("longitude")-lon)*scale,y=p.getDouble("latitude")-lat;
                double dx=(q.getDouble("longitude")-p.getDouble("longitude"))*scale,dy=q.getDouble("latitude")-p.getDouble("latitude");
                double d=dx*dx+dy*dy,t=d==0?0:Math.max(0,Math.min(1,-(x*dx+y*dy)/d));
                double dist=Math.hypot(x+t*dx,y+t*dy)*111195;
                if(dist<local){local=dist;la=p.getDouble("latitude")+t*dy;lo=p.getDouble("longitude")+t*(q.getDouble("longitude")-p.getDouble("longitude"));}
            }
            if(local<best){second=best;best=local;leg=i;blat=la;blon=lo;}else if(local<second)second=local;
        }
        return new double[]{leg,best,blat,blon,second};
    }
    private JSONArray originalLegs(JSONObject route)throws Exception{
        JSONArray legs=route.optJSONArray("original_legs");return legs==null?route.getJSONArray("legs"):legs;
    }
    private int suggestedIndex(JSONObject route,LatLng point)throws Exception{
        double[] hit=nearestLeg(originalLegs(route),point.getLatitude(),point.getLongitude());
        JSONArray stops=route.getJSONArray("stops");
        int after=(int)hit[0]+(route.optJSONObject("shop")==null?1:0);
        if(hit[0]<0)return 0;
        int n=0,i=0;
        while(i<stops.length()&&n<after){if(!stops.getJSONObject(i).optBoolean("added"))n++;i++;}
        return i;
    }
    private void cancelDraft(){
        clearDraftMarker();draftPoint=null;draftOriginal=null;
        if(draftResume!=null){rootFrame.removeView(draftResume);draftResume=null;}
    }
    private void beginAddDelivery(LatLng point){
        if(deliveryRoute==null||readyStyle==null||pendingSlot==null)return;
        if(deliveryRoute.optJSONArray("stops").length()>=150)return;
        try{
            if(draftOriginal!=deliveryRoute){cancelDraft();draftOriginal=deliveryRoute;draftSlot=pendingSlot;draftAddress="";}
            draftPoint=point;draftIndex=suggestedIndex(draftOriginal,point);
            clearDraftMarker();
            readyStyle.addSource(new GeoJsonSource("draft-delivery-source",Feature.fromGeometry(Point.fromLngLat(point.getLongitude(),point.getLatitude()))));
            org.maplibre.android.style.layers.CircleLayer marker=new org.maplibre.android.style.layers.CircleLayer("draft-delivery","draft-delivery-source");
            marker.withProperties(PropertyFactory.circleColor(0xFFFF8800),PropertyFactory.circleRadius(12f),PropertyFactory.circleStrokeColor(Color.WHITE),PropertyFactory.circleStrokeWidth(2f));readyStyle.addLayer(marker);
            if(draftResume==null)openDraft();
        }catch(Exception e){Toast.makeText(this,e.toString(),Toast.LENGTH_LONG).show();}
    }
    private void openDraft(){
        if(draftOriginal==null||draftOriginal!=deliveryRoute){cancelDraft();return;}
        if(draftResume!=null){rootFrame.removeView(draftResume);draftResume=null;}
        JSONArray stops=draftOriginal.optJSONArray("stops");
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView coords=new TextView(this);coords.setText(String.format(Locale.US,"%.5f, %.5f",draftPoint.getLatitude(),draftPoint.getLongitude()));form.addView(coords);
        android.widget.EditText address=new android.widget.EditText(this);address.setHint("Street address, town, MA");address.setText(draftAddress);form.addView(address);
        TextView label=new TextView(this);label.setText("Add after (verify suggested position):");form.addView(label);
        Spinner position=new Spinner(this);List<String> choices=new ArrayList<>();choices.add("0 — Before first delivery");
        for(int i=0;i<stops.length();i++)choices.add((i+1)+" — "+stops.optJSONObject(i).optString("address"));
        position.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,choices));position.setSelection(draftIndex);form.addView(position);
        draftDialog=new android.app.AlertDialog.Builder(this).setTitle("Add delivery to "+draftSlot).setView(form).setNegativeButton("Cancel",(d,w)->cancelDraft()).setNeutralButton("Minimize",(d,w)->{
            draftAddress=address.getText().toString();draftIndex=position.getSelectedItemPosition();
            draftResume=new Button(this);draftResume.setText("New delivery • reopen");draftResume.setTextColor(0xFFE88A19);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);lp.bottomMargin=dp(65);rootFrame.addView(draftResume,lp);draftResume.setOnClickListener(v->openDraft());
        }).setPositiveButton("Save",null).create();
        draftDialog.setOnCancelListener(d->cancelDraft());draftDialog.show();
        draftDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=address.getText().toString().trim();if(name.isEmpty()){address.setError("Enter delivery address");return;}
            try{insertDelivery(draftSlot,draftOriginal,draftPoint,name,position.getSelectedItemPosition());draftDialog.dismiss();cancelDraft();}
            catch(Exception e){Toast.makeText(this,"Cannot add delivery: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        });
    }
    private JSONObject draftLeg(JSONObject a,JSONObject b)throws Exception{
        JSONArray pts=new JSONArray();for(JSONObject s:new JSONObject[]{a,b})pts.put(new JSONObject().put("latitude",s.getDouble("lat")).put("longitude",s.getDouble("lon")));
        return new JSONObject().put("unrouted",true).put("points",pts).put("summary",new JSONObject().put("lengthInMeters",0));
    }
    private void insertDelivery(String slot,JSONObject original,LatLng point,String address,int index)throws Exception{
        android.util.AtomicFile file=RouteInstaller.file(this,slot);
        JSONObject route=RouteInstaller.validate(new String(file.readFully(),StandardCharsets.UTF_8));
        if(!route.toString().equals(original.toString()))throw new Exception("Route changed; select location again");
        rememberEdit(route);
        JSONArray old=route.getJSONArray("stops"),stops=new JSONArray(),legs=new JSONArray();
        JSONArray preserved=originalLegs(route);
        for(int i=0;i<preserved.length();i++)if(preserved.getJSONObject(i).optBoolean("unrouted"))throw new Exception("Reinstall original office route before adding stops");
        route.put("original_legs",preserved);
        JSONObject added=new JSONObject().put("address",address).put("lat",point.getLatitude()).put("lon",point.getLongitude()).put("added",true);
        for(int i=0;i<=old.length();i++){if(i==index)stops.put(added);if(i<old.length())stops.put(old.getJSONObject(i));}
        JSONObject shop=route.optJSONObject("shop");List<JSONObject> nodes=new ArrayList<>();if(shop!=null)nodes.add(shop);
        for(int i=0;i<stops.length();i++)nodes.add(stops.getJSONObject(i));if(shop!=null)nodes.add(shop);
        for(int i=0;i<nodes.size()-1;i++)legs.put(draftLeg(nodes.get(i),nodes.get(i+1)));
        route.put("stops",stops).put("legs",legs);
        String raw=route.toString();RouteInstaller.validate(raw);
        java.io.FileOutputStream out=file.startWrite();
        try{out.write(raw.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){file.failWrite(out);throw e;}
        int next=nextStop+(index<nextStop?1:0);
        String key=slot+"_"+route.optString("date","")+"_"+Integer.toHexString(stops.toString().hashCode());
        prefs().edit().putInt("next_"+key,next).putInt("scroll_"+key,deliveryScroll.getScrollY()).apply();
        org.maplibre.android.camera.CameraPosition camera=readyMap.getCameraPosition();boolean rotate=rotationUnlocked;
        showSavedRoute(slot);rotationUnlocked=rotate;readyMap.getUiSettings().setRotateGesturesEnabled(rotate);
        readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(camera));updateCompass();saveView();
        Toast.makeText(this,"Delivery added — original route preserved",Toast.LENGTH_SHORT).show();
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
        if(moveOriginal!=null)clearMove();
        try {
            android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(getFilesDir(),"route_"+slot+".json"));
            JSONObject route=RouteInstaller.validate(new String(file.readFully(),StandardCharsets.UTF_8));
            pendingSlot=slot;
            demoRouteShown=false;
            if(readyStyle==null || readyMap==null) return;
            JSONArray stops=route.getJSONArray("stops"), legs=originalLegs(route);
            List<Feature> markers=new ArrayList<>(), lines=new ArrayList<>(), unrouted=new ArrayList<>();
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
                readyStyle.addImage(icon,numberIcon(i+1,count,stopColor(s,0xFF1A6DB3)));
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
                    if(legs.getJSONObject(i).optBoolean("unrouted"))unrouted.add(f);else lines.add(f);
                }
            }
            for(int i=0;i<stops.length();i++){
                JSONObject s=stops.getJSONObject(i);if(s.optBoolean("deleted")||(!s.optBoolean("added")&&!s.optBoolean("relocated")))continue;
                double lat=s.getDouble("lat"),lon=s.getDouble("lon");double[] hit=nearestLeg(legs,lat,lon);
                if(hit[0]>=0&&hit[1]>10)unrouted.add(Feature.fromGeometry(LineString.fromLngLats(java.util.Arrays.asList(Point.fromLngLat(lon,lat),Point.fromLngLat(hit[3],hit[2])))));
            }
            readyStyle.addSource(new GeoJsonSource("route-source",FeatureCollection.fromFeatures(lines)));
            addStyledRouteLine(readyStyle,"tomtom-route-line",lines);
            if(!unrouted.isEmpty()){readyStyle.addSource(new GeoJsonSource("unrouted-source",FeatureCollection.fromFeatures(unrouted)));LineLayer dashed=new LineLayer("unrouted-line","unrouted-source");dashed.withProperties(PropertyFactory.lineColor(0xFFFF8800),PropertyFactory.lineWidth(4f),PropertyFactory.lineDasharray(new Float[]{2f,2f}));readyStyle.addLayer(dashed);}
            readyStyle.addSource(new GeoJsonSource("stops-source",FeatureCollection.fromFeatures(markers)));
            SymbolLayer layer=new SymbolLayer("numbered-stops","stops-source");
            layer.withProperties(PropertyFactory.iconImage(Expression.get("icon")),PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),PropertyFactory.iconSize(markerPercent/100f));
            readyStyle.addLayer(layer);
            try {
                routeBounds=bounds.build();
                lockNorth();
                readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(routeBounds,90));
            } catch(Exception e) {
                JSONObject s=stops.getJSONObject(0);
                readyMap.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(new LatLng(s.getDouble("lat"),s.getDouble("lon")),16));
            }
            long meters=0,seconds=0; boolean timeKnown=true;
            for(int i=0;i<legs.length();i++){JSONObject summary=legs.getJSONObject(i).getJSONObject("summary");meters+=summary.getLong("lengthInMeters");long s=summary.optLong("travelTimeInSeconds",-1);if(s<0)timeKnown=false;else seconds+=s;}
            int deleted=0;for(int i=0;i<stops.length();i++)if(stops.optJSONObject(i).optBoolean("deleted"))deleted++;
            status.setText(RouteInstaller.label(this,slot)+" • "+(deleted==0?stops.length()+" stops":(stops.length()-deleted)+" active\n"+deleted+" deleted"));
            status.setGravity(Gravity.LEFT);
            long minutes=Math.round(seconds/60.0);
            mileage.setText((route.has("original_legs")?"Original: "+String.format(Locale.US,"%.1f mi",meters/1609.344):unrouted.isEmpty()?String.format(Locale.US,"%.1f mi",meters/1609.344):"Partial: "+String.format(Locale.US,"%.1f mi",meters/1609.344))+"\n"+(timeKnown?(minutes/60>0?minutes/60+" hr ":"")+minutes%60+" min":"Time unavailable")+"\n"+("here".equals(route.optString("provider"))?"HERE":"TomTom"));
            populateDelivery(route,slot);
            saveView();
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
    private Bitmap numberIcon(int n,int count) { return numberIcon(n,count,false); }
    private Bitmap numberIcon(int n,int count,boolean added) {return numberIcon(n,count,added?0xFFE88A19:0xFF1A6DB3);}
    private Bitmap numberIcon(int n,int count,int color) {
        Bitmap b = Bitmap.createBitmap(88, 88, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(n==0?0xFF278344:color); c.drawCircle(44, 44, 39, p);
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
        StringBuilder b=new StringBuilder("\nOFFLINE DATABASE READ-ONLY INSPECTION v1.4.18\n");
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

    @Override public void onBackPressed(){if(moveOriginal!=null){finishMove(false);return;}super.onBackPressed();}
    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    private final android.content.BroadcastReceiver routeChanged=new android.content.BroadcastReceiver(){
 @Override public void onReceive(Context c,android.content.Intent i){refreshSlotLabels();if(i.getStringExtra("route").equals(pendingSlot))showSavedRoute(pendingSlot);}
 };
 private void refreshSlotLabels(){for(String s:routeSlots){Button b=slotButtons.get(s);if(b!=null)b.setText(RouteInstaller.label(this,s));}}
    @Override protected void onResume() { super.onResume(); hideFloating(); mapView.onResume(); refreshSlotLabels(); }
    @Override protected void onPause() { saveView();if(deliveryRoute!=null&&!panelCollapsed)prefs().edit().putInt("scroll_"+deliveryKey,deliveryScroll.getScrollY()).apply(); mapView.onPause(); super.onPause(); }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override protected void onDestroy() { clearMove(); hideFloating(); navHandler.removeCallbacksAndMessages(null); retryHandler.removeCallbacksAndMessages(null); if(downloading && offlineRegion!=null){activeRegionIndex=-1;offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE);} unregisterReceiver(routeChanged); mapView.onDestroy(); super.onDestroy(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); mapView.onSaveInstanceState(state);
    }
}
