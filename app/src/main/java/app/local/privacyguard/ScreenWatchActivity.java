package app.local.privacyguard;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

/** Controls and live status for the ordinary application's independent monitor. */
public final class ScreenWatchActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS=61;
    private TextView status,access,unplugged;
    private Switch enabled,hints;
    private Button detail;
    private boolean repaint;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refreshLoop=new Runnable(){public void run(){refresh();handler.postDelayed(this,1500);}};

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setTitle("手机投屏监测");
        GuardUi ui=new GuardUi(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout page=ui.col();ui.pad(page,20,20);page.setBackgroundColor(GuardUi.BG);
        scroll.addView(page);setContentView(scroll);
        ui.add(page,ui.action("返回",v->finish()),0);
        ui.add(page,ui.text("手机投屏监测",26,GuardUi.INK,true),2);
        enabled=new Switch(this);enabled.setText("在手机上持续监测");enabled.setMinHeight(ui.dp(54));ui.add(page,enabled,6);
        status=ui.text("",16,GuardUi.TEAL,true);ui.add(page,status,3);
        access=ui.text("",14,GuardUi.MUTED,false);ui.add(page,access,8);
        unplugged=ui.text("",14,GuardUi.TEAL,false);ui.add(page,unplugged,4);
        ui.add(page,ui.text("监测在本应用进程运行，保留你的按键截图。发现标准共享会话时会提醒；在系统录屏通知里结束共享，或打开来源应用的系统详情。系统内部取屏和无障碍读屏可能没有标准会话。",15,GuardUi.INK,false),12);
        detail=ui.action("打开来源应用的系统详情",v->{
            String pkg=ScreenWatchService.source;
            if(!pkg.isEmpty())settings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",pkg,null)));
        });ui.add(page,detail,2);
        hints=new Switch(this);hints.setText("允许所选应用的前台服务提示");hints.setMinHeight(ui.dp(52));ui.add(page,hints,16);
        ui.add(page,ui.text("系统会话读不到时，可利用已连接的通知过滤服务，仅检查已选应用的新前台通知标志及其服务声明。音乐播放等服务也可能触发提示；多长时间未收到提示都不能排除录屏。",14,GuardUi.MUTED,false),2);
        ui.add(page,ui.action("选择需要提示的应用",v->startActivity(new Intent(this,UsbPolicyActivity.class))),4);
        ui.add(page,ui.text("在应用规则中勾选屏幕共享项。本页的提示不会强制停止应用；设备规则执行器在线时，已保存的停止规则仍可独立生效。",14,GuardUi.MUTED,false),0);
        ui.add(page,ui.action("守护通知设置",v->settings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()))),8);
        hints.setOnCheckedChangeListener((v,on)->{if(!repaint)Prefs.set(this,ScreenWatchService.HINTS,on);});
        enabled.setOnCheckedChangeListener((v,on)->{
            if(repaint)return;
            if(!on){ScreenWatchService.stop(this);refresh();return;}
            if(needsNotificationPermission()){
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},REQUEST_NOTIFICATIONS);
            }else if(ScreenWatchService.notificationsAllowed(this))ScreenWatchService.start(this);
            else{
                Toast.makeText(this,"请在系统通知设置中允许守护显示提醒，再开启监测。",Toast.LENGTH_LONG).show();
                settings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));
            }
            refresh();
        });
    }
    @SuppressWarnings("deprecation")
    private boolean needsNotificationPermission(){
        String permission=android.Manifest.permission.POST_NOTIFICATIONS;
        if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)return false;
        try{
            android.content.pm.PermissionInfo info=getPackageManager().getPermissionInfo(permission,0);
            return (info.protectionLevel&android.content.pm.PermissionInfo.PROTECTION_MASK_BASE)==
                    android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
        }catch(PackageManager.NameNotFoundException e){return false;}
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] results){
        super.onRequestPermissionsResult(request,names,results);
        if(request==REQUEST_NOTIFICATIONS && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)
            ScreenWatchService.start(this);
        refresh();
    }
    private void settings(Intent i){
        try{startActivity(i);}catch(RuntimeException e){Toast.makeText(this,"系统未提供该设置入口",Toast.LENGTH_SHORT).show();}
    }
    private void refresh(){
        boolean want=Prefs.get(this,ScreenWatchService.ENABLED),running=ScreenWatchService.running();
        repaint=true;enabled.setChecked(want||running);hints.setChecked(ScreenWatchService.hints(this));repaint=false;
        String text=ScreenWatchService.summary;
        if(!running&&want)text="监测进程未运行，关闭再开启可重试";
        else if(running&&ScreenWatchService.sampled>0 && SystemClock.elapsedRealtime()-ScreenWatchService.sampled>20000)
            text="监测结果已过期，等待手机服务重新采样";
        status.setText(text);
        boolean permission=checkSelfPermission(android.Manifest.permission.DUMP)==PackageManager.PERMISSION_GRANTED;
        access.setText(permission?"系统诊断权限已授予；实际可读性以上方结果为准。":
                "系统诊断权限未授予。可通过一次 USB 配置授予，本应用只查询标准投屏服务。");
        long sample=Prefs.prefs(this).getLong(ScreenWatchService.UNPLUG_SAMPLE,0);
        unplugged.setVisibility(sample>0?android.view.View.VISIBLE:android.view.View.GONE);
        if(sample>0){
            java.text.DateFormat fmt=java.text.DateFormat.getDateTimeInstance();
            String evidence="最近一次断线期间验证成功 · "+fmt.format(new java.util.Date(sample));
            long active=Prefs.prefs(this).getLong(ScreenWatchService.UNPLUG_ACTIVE,0);
            if(active>0)evidence+="\n最近一次断线期间检测到标准会话 · "+fmt.format(new java.util.Date(active));
            unplugged.setText(evidence);
        }
        detail.setEnabled(running&&!ScreenWatchService.source.isEmpty());
    }
    @Override protected void onResume(){super.onResume();refreshLoop.run();}
    @Override protected void onPause(){handler.removeCallbacks(refreshLoop);super.onPause();}
}
