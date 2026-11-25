package app.local.privacyguard;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.util.concurrent.*;

/** Visible app-owned monitor. Neither takes frames nor stops any other process. */
public final class ScreenWatchService extends Service {
    static final String ENABLED="phone_screen_watch_enabled", HINTS="phone_screen_watch_hints";
    static final String UNPLUG_SAMPLE="screen_watch_usb_disconnected_sample";
    static final String UNPLUG_ACTIVE="screen_watch_usb_disconnected_standard_session";
    private static final String STOP="app.local.privacyguard.STOP_SCREEN_WATCH";
    private static final String LIVE_CHANNEL="private_screen_watch", ALERT_CHANNEL="private_screen_events";
    private static final int LIVE_ID=8130, ALERT_ID=8131;
    private static volatile ScreenWatchService live;
    static volatile String summary="监测已关闭", source="";
    static volatile long sampled;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> task;
    private volatile String hintPackage="";
    private volatile long hintTime;
    private String lastKey="";
    private ScreenSessionReport.Reading reading;
    private long retry;
    private boolean sampledUnplugged;
    private String sampledSource="";
    private volatile long lastUsbSample,lastUsbActive;
    private long persistedAt;
    static boolean running(){return live!=null;}
    static boolean hints(Context c){return Prefs.prefs(c).getBoolean(HINTS,false);}
    static boolean notificationsAllowed(Context c){
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(nm==null||!nm.areNotificationsEnabled())return false;
        for(String id:new String[]{LIVE_CHANNEL,ALERT_CHANNEL}){
            NotificationChannel ch=nm.getNotificationChannel(id);
            if(ch!=null&&ch.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;
        }
        return true;
    }
    static void start(Context c){
        if(!notificationsAllowed(c)){summary="请先在系统设置允许守护通知与投屏提醒";return;}
        Prefs.set(c,ENABLED,true);
        try{c.startForegroundService(new Intent(c,ScreenWatchService.class));}
        catch(RuntimeException e){Prefs.set(c,ENABLED,false);summary="系统未允许启动监测，请在此页面重试";}
    }
    static void stop(Context c){
        Prefs.set(c,ENABLED,false);c.stopService(new Intent(c,ScreenWatchService.class));summary="监测已关闭";source="";
    }
    static void observeNotification(Context context,StatusBarNotification posted){
        ScreenWatchService svc=live;
        if(svc==null||posted==null||!hints(context)||Build.VERSION.SDK_INT<29||
                (posted.getNotification().flags&Notification.FLAG_FOREGROUND_SERVICE)==0)return;
        String pkg=posted.getPackageName();
        String identity=UsbPolicyProvider.identity(context,pkg,false);
        int rule=UsbPolicyProvider.rule(UsbPolicyProvider.prefs(context),pkg,identity);
        if(identity==null||rule<0||(rule&UsbPolicyProvider.SCREEN)==0)return;
        try{
            PackageInfo info=context.getPackageManager().getPackageInfo(pkg,PackageManager.GET_SERVICES);
            if(info.applicationInfo==null||posted.getUid()!=info.applicationInfo.uid||info.services==null)return;
            for(ServiceInfo service:info.services){
                if((service.getForegroundServiceType()&ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)==0)continue;
                svc.hintPackage=pkg;svc.hintTime=SystemClock.elapsedRealtime();return;
            }
        }catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
    }
    private NotificationManager manager(){return getSystemService(NotificationManager.class);}
    private void saveUsbTimes(){
        if(lastUsbSample<=0)return;
        SharedPreferences.Editor p=Prefs.prefs(this).edit().putLong(UNPLUG_SAMPLE,lastUsbSample);
        if(lastUsbActive>0)p.putLong(UNPLUG_ACTIVE,lastUsbActive);
        p.apply();
        persistedAt=SystemClock.elapsedRealtime();
    }
    private void checkDisconnectedSample(){
        try{
            Intent state=registerReceiver(null,new IntentFilter("android.hardware.usb.action.USB_STATE"));
            if(state==null||!state.hasExtra("connected"))return;
            if(state.getBooleanExtra("connected",true)){
                if(sampledUnplugged)saveUsbTimes();
                sampledUnplugged=false;sampledSource="";return;
            }
            String active=reading.source;
            // Only timestamps persist. Save transitions and periodic progress without writing on every poll.
            lastUsbSample=System.currentTimeMillis();
            if(!active.isEmpty())lastUsbActive=lastUsbSample;
            if(!sampledUnplugged||!active.equals(sampledSource)||SystemClock.elapsedRealtime()-persistedAt>=60000)saveUsbTimes();
            sampledUnplugged=true;
            sampledSource=active;
        }catch(RuntimeException ignored){}
    }
    private Notification notice(String channel,String title,String text,boolean ongoing){
        PendingIntent open=PendingIntent.getActivity(this,LIVE_ID,new Intent(this,ScreenWatchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,channel).setSmallIcon(R.drawable.guard_icon)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(ongoing).setOnlyAlertOnce(ongoing);
        if(ongoing){
            PendingIntent end=PendingIntent.getService(this,LIVE_ID,new Intent(this,ScreenWatchService.class).setAction(STOP),
                    PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null,"停止监测",end).build());
            if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
    @Override public int onStartCommand(Intent command,int flags,int startId){
        if(command!=null&&STOP.equals(command.getAction()))Prefs.set(this,ENABLED,false);
        if(!Prefs.get(this,ENABLED)){stopSelf();return START_NOT_STICKY;}
        try{
            manager().createNotificationChannel(new NotificationChannel(LIVE_CHANNEL,"手机投屏监测状态",NotificationManager.IMPORTANCE_LOW));
            manager().createNotificationChannel(new NotificationChannel(ALERT_CHANNEL,"发现投屏或提示",NotificationManager.IMPORTANCE_HIGH));
            if(!notificationsAllowed(this))throw new IllegalStateException();
            startForeground(LIVE_ID,notice(LIVE_CHANNEL,"手机投屏监测","正在核实系统能否提供会话状态",true));
        }catch(RuntimeException error){
            Prefs.set(this,ENABLED,false);stopSelf();summary="系统未允许持续显示监测通知";return START_NOT_STICKY;
        }
        if(live!=this){live=this;summary="正在核实系统会话接口";sampled=0;}
        if(task==null)task=worker.scheduleWithFixedDelay(this::tick,0,3,TimeUnit.SECONDS);
        return START_STICKY;
    }
    private void tick(){
        try{
            if(live!=this||!Prefs.get(this,ENABLED))return;
            long now=SystemClock.elapsedRealtime();
            if(reading==null||now>=retry){
                reading=ScreenSessionReport.query(this);
                retry=now+(reading.confirmed?0:15000);
            }
            if(live!=this||!Prefs.get(this,ENABLED))return;
            String alert="",pkg="",text,key;
            if(reading.confirmed){
                checkDisconnectedSample();
                pkg=reading.source;
                if(pkg.isEmpty()){text="本次查询未发现标准屏幕共享会话";key="none";}
                else{alert="检测到系统屏幕共享";text="系统已报告活动会话 · "+pkg;key="confirmed:"+pkg;}
            }else if(hints(this)&&AdNotificationService.isConnected()&&!hintPackage.isEmpty()&&now-hintTime<=60000){
                pkg=hintPackage;
                alert="所选应用发出前台服务提示";
                text="过去一分钟发现 "+pkg+" 的前台通知。该应用声明了录屏服务，实际录屏状态未确认。";
                key="hint:"+pkg;
            }else{
                text=reading.reason+"。"+(hints(this)?(AdNotificationService.isConnected()?"仅等待所选应用的新前台服务提示。":"前台服务提示等待通知过滤服务连接。"):"无法判断当前是否录屏。");
                key="unavailable:"+text;
            }
            sampled=now;summary=text;source=pkg;
            if(!key.equals(lastKey)){
                manager().notify(LIVE_ID,notice(LIVE_CHANNEL,"手机投屏监测",text,true));
                if(alert.isEmpty())manager().cancel(ALERT_ID);else manager().notify(ALERT_ID,notice(ALERT_CHANNEL,alert,text,false));
                lastKey=key;
            }
        }catch(RuntimeException e){summary="本次监测未取得有效结果";source="";sampled=SystemClock.elapsedRealtime();}
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        if(live==this){live=null;sampled=0;source="";summary="监测服务已停止";}
        if(task!=null)task.cancel(true);worker.shutdownNow();
        saveUsbTimes();
        manager().cancel(ALERT_ID);stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
