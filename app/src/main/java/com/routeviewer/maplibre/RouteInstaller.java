package com.routeviewer.maplibre;
import android.content.*;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
public class RouteInstaller extends BroadcastReceiver {
 public static final String ACTION="com.routeviewer.maplibre.INSTALL_ROUTE";
 static AtomicFile file(Context c,String slot){return new AtomicFile(new File(c.getFilesDir(),"route_"+slot+".json"));}
 static String label(Context c,String slot){
 try{
 JSONObject j=new JSONObject(new String(file(c,slot).readFully(),StandardCharsets.UTF_8));
 LocalDate date=LocalDate.parse(j.getString("date"));
 String fallback=slot+"-"+date.getDayOfMonth();
 java.util.regex.Matcher m=java.util.regex.Pattern.compile("^(.+?)\\s+(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+"+slot+"(?:\\s|$)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(j.optString("list_name","").trim());
 if(!m.find())return fallback;
 return m.group(1).trim()+" "+date.format(java.time.format.DateTimeFormatter.ofPattern("EEE",java.util.Locale.US))+" "+fallback;
 }catch(Exception e){return slot;}
 }
 static void point(double lat,double lon)throws Exception{if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)throw new Exception("Invalid coordinate");}
 static JSONObject validate(String raw)throws Exception{
 JSONObject j=new JSONObject(raw);String slot=j.getString("route");
 if(!slot.equals("AM")&&!slot.equals("PM")&&!slot.equals("EX"))throw new Exception("Invalid slot");
 if(!"tomtom".equals(j.getString("provider"))&&!"here".equals(j.getString("provider")))throw new Exception("Invalid provider");
 if(j.has("date"))LocalDate.parse(j.getString("date"));
 JSONArray stops=j.getJSONArray("stops"),legs=j.getJSONArray("legs");
 JSONObject shop=j.optJSONObject("shop");
 if(shop!=null)point(shop.getDouble("lat"),shop.getDouble("lon"));
 if(stops.length()<1||stops.length()>150||legs.length()!=stops.length()+(shop==null?-1:1))throw new Exception("Invalid stop or leg count");
 for(int i=0;i<stops.length();i++){JSONObject s=stops.getJSONObject(i);point(s.getDouble("lat"),s.getDouble("lon"));s.getString("address");}
 for(int i=0;i<legs.length();i++){JSONObject l=legs.getJSONObject(i);long m=l.getJSONObject("summary").getLong("lengthInMeters");JSONArray pts=l.getJSONArray("points");if(m<0||(m>0&&pts.length()<2))throw new Exception("Invalid leg");for(int k=0;k<pts.length();k++){JSONObject p=pts.getJSONObject(k);point(p.getDouble("latitude"),p.getDouble("longitude"));}}
 return j;
 }
 @Override public void onReceive(Context c,Intent intent){
 if(!ACTION.equals(intent.getAction()))return;
 PendingResult pending=goAsync();
 new Thread(()->{String slot="",date="",error="";boolean ok=false;
 try{
 android.net.Uri uri=intent.getData();if(uri==null||!"content".equals(uri.getScheme()))throw new Exception("Content URI required");
 ByteArrayOutputStream bytes=new ByteArrayOutputStream();
 try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null)throw new Exception("Cannot open file");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>8*1024*1024)throw new Exception("File too large");bytes.write(b,0,n);}}
 JSONObject j=validate(bytes.toString("UTF-8"));slot=j.getString("route");date=j.getString("date");if(j.optJSONObject("shop")==null)throw new Exception("Shop required for automatic installation");
 synchronized(RouteInstaller.class){AtomicFile f=file(c,slot);FileOutputStream out=f.startWrite();try{out.write(bytes.toByteArray());f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}}ok=true;
 }catch(Exception e){error=e.toString();}
 finally{try{Intent ack=new Intent("com.routeviewer.maplibre.ROUTE_INSTALLED");ack.setPackage("net.dinglisch.android.taskerm");ack.putExtra("success",Boolean.toString(ok));ack.putExtra("route",slot);ack.putExtra("date",date);ack.putExtra("error",error.isEmpty()?"none":error);ack.putExtra("request_id",intent.getStringExtra("request_id"));c.sendBroadcast(ack);Intent changed=new Intent("com.routeviewer.maplibre.ROUTE_CHANGED").setPackage(c.getPackageName());changed.putExtra("route",slot);if(ok)c.sendBroadcast(changed);}finally{pending.finish();}}
 }).start();
 }
}
