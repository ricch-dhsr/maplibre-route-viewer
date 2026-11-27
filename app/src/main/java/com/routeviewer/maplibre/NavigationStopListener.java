package com.routeviewer.maplibre;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
public class NavigationStopListener extends NotificationListenerService {
    private static volatile NavigationStopListener active;
    @Override public void onListenerConnected(){super.onListenerConnected();active=this;}
    @Override public void onListenerDisconnected(){active=null;super.onListenerDisconnected();}
    @Override public void onDestroy(){active=null;super.onDestroy();}
    public static String stopNavigation(){
        NavigationStopListener service=active;
        if(service==null)return "Notification access unavailable";
        StringBuilder result=new StringBuilder();
        try{
            StatusBarNotification[] notifications=service.getActiveNotifications();
            for(String pkg:new String[]{"com.google.android.apps.maps","net.osmand"}){
                boolean sent=false;
                for(StatusBarNotification sb:notifications){
                    if(sent||!pkg.equals(sb.getPackageName()))continue;
                    Notification.Action[] actions=sb.getNotification().actions;
                    if(actions==null)continue;
                    for(Notification.Action action:actions){
                        String title=String.valueOf(action.title).trim();
                        if((pkg.equals("net.osmand")&&title.equalsIgnoreCase("Stop"))||(pkg.equals("com.google.android.apps.maps")&&title.equalsIgnoreCase("Exit navigation"))){
                            if(action.actionIntent==null)continue;
                            try{action.actionIntent.send();sent=true;break;}catch(Exception e){result.append(pkg).append(": ").append(e).append("; ");}
                        }
                    }
                }
                result.append(pkg.equals("net.osmand")?"OsmAnd":"Maps").append(sent?": stop requested; ":": no stop action; ");
            }
        }catch(Exception e){result.append(e);}
        return result.toString();
    }
}
