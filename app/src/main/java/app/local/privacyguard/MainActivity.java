package app.local.privacyguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.LinkedHashSet;

public final class MainActivity extends Activity {
    private static final int VPN_REQUEST=21, BROWSER_REQUEST=22;
    private GuardUi u;
    private LinearLayout panel;
    private TextView summary;
    private Card links,dns,notices,screen,managed;
    private boolean repaint;
    private final Handler handler=new Handler(android.os.Looper.getMainLooper());
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1800);}};
    private interface Toggle {void change(boolean on);}
    private static final class Card {Switch sw;TextView status;LinearLayout box;}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);AdDnsService.reconcileOnOpen(this);setTitle("隐私守护");u=new GuardUi(this);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(GuardUi.BG);
        panel=u.col();u.pad(panel,20,20);scroll.addView(panel);setContentView(scroll);
        LinearLayout head=u.col();u.pad(head,18,18);head.setBackground(u.shape(0xFFE3EFE6,18,0));u.add(panel,head,0);
        head.addView(u.text("隐私守护",30,GuardUi.INK,true));
        summary=u.text("",15,GuardUi.MUTED,false);u.add(head,summary,4);
        u.add(head,u.action("系统级按应用控制",v->startActivity(new Intent(this,UsbPolicyActivity.class))),6);
        u.add(head,u.action("安装来源与新增应用",v->startActivity(new Intent(this,InstallGuardActivity.class))),0);
        u.add(head,u.action("独立投屏监测",v->startActivity(new Intent(this,ScreenWatchActivity.class))),0);
        links=card("网页跳转确认","作为默认浏览器接收 HTTP / HTTPS 链接。广告域名直接拦截，其他外部网页由你决定是否打开。应用内网页和直接唤起另一个应用的跳转可能绕过。",on->{
            Prefs.set(this,Prefs.LINKS,on);if(on&&!isBrowser())browserSettings();});
        button(links,"选择默认浏览器",v->browserSettings());
        Switch denyWeb=new Switch(this);
        denyWeb.setText("拦下所有交给守护的网页跳转");
        denyWeb.setMinHeight(u.dp(48));
        denyWeb.setChecked(Prefs.get(this,Prefs.DENY_WEB));
        links.box.addView(denyWeb);
        denyWeb.setOnCheckedChangeListener((v,enabled)->Prefs.set(this,Prefs.DENY_WEB,enabled));
        u.add(links.box,u.text("随网页保护生效。开启后，你主动点开的外部网页也会被拦下。",14,GuardUi.MUTED,false),0);
        if("HUAWEI".equalsIgnoreCase(Build.MANUFACTURER)){
            button(links,"华为设备方向权限",v->startActivity(new Intent(this,MotionGuardActivity.class)));
        }
        dns=card("广告域名拦截","通过本地 VPN 过滤系统 DNS 查询，与网页跳转共用域名规则。此功能需要独占 VPN；应用自带加密 DNS、直连 IP 或同域广告可能绕过。",this::toggleDns);
        button(dns,"编辑拦截域名",v->domainEditor());
        notices=card("广告通知过滤","只处理你选中的应用。通知正常发布后按规则移除；横幅、声音可能先出现。",on->{
            Prefs.set(this,Prefs.NOTICES,on);AdNotificationService.applyEnabled(this);
            if(on&&!AdNotificationService.hasAccess(this))notificationSettings();
            else if(on&&NotificationRulesActivity.ruleCount(this)==0)startActivity(new Intent(this,NotificationRulesActivity.class));
        });
        button(notices,"选择应用与过滤规则",v->startActivity(new Intent(this,NotificationRulesActivity.class)));
        button(notices,"管理系统通知访问",v->notificationSettings());
        screen=card("守护窗口防截屏","可选。启用后也会限制你对本应用的截图。按应用监视屏幕共享请用“系统级按应用控制”。",on->{Prefs.set(this,Prefs.SECURE,on);GuardApp.protect(this);});
        button(screen,"打开私密文件预览",v->startActivity(new Intent(this,SecureViewerActivity.class)));
        DevicePolicyManager dpm=getSystemService(DevicePolicyManager.class);
        if(dpm!=null&&(dpm.isDeviceOwnerApp(getPackageName())||dpm.isProfileOwnerApp(getPackageName()))){
            boolean whole=dpm.isDeviceOwnerApp(getPackageName());
            managed=card(whole?"受管设备防截屏":"工作资料防截屏",whole?"由本应用的设备所有者策略限制截屏。":"由本应用的资料所有者策略限制工作资料中的截屏。",on->{
                try{dpm.setScreenCaptureDisabled(new ComponentName(this,PolicyReceiver.class),on);}
                catch(SecurityException|IllegalStateException e){info("策略未更改","当前系统没有授予更改此策略的权限。");}
            });
        }
        Button joystick=u.action("打开定位摇杆网页",v->startActivity(new Intent(this,LocationActivity.class)));
        u.add(panel,joystick,10);
        Button privacy=u.action("打开系统隐私设置",v->systemSettings(Settings.ACTION_PRIVACY_SETTINGS));
        u.add(panel,privacy,10);
        refresh();
    }
    private Card card(String title,String help,Toggle change){
        Card c=new Card();c.box=u.col();u.pad(c.box,16,12);c.box.setBackground(u.shape(0xFFFFFFFF,16,0xFFDAE3DD));u.add(panel,c.box,12);
        c.sw=new Switch(this);c.sw.setText(title);c.sw.setTextSize(19);c.sw.setTextColor(GuardUi.INK);c.sw.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.sw.setMinHeight(u.dp(48));c.sw.setSwitchPadding(u.dp(12));c.box.addView(c.sw);
        c.status=u.text("",14,GuardUi.TEAL,true);u.add(c.box,c.status,2);u.add(c.box,u.text(help,14,GuardUi.MUTED,false),6);
        c.sw.setOnCheckedChangeListener((button,checked)->{if(!repaint){change.change(checked);refresh();}});return c;
    }
    private void button(Card c,String label,View.OnClickListener on){u.add(c.box,u.action(label,on),0);}
    private void show(Card c,boolean selected,String status){c.sw.setChecked(selected);c.status.setText(status);}
    private boolean isBrowser(){
        if(Build.VERSION.SDK_INT>=29){RoleManager r=getSystemService(RoleManager.class);return r!=null&&r.isRoleHeld(RoleManager.ROLE_BROWSER);}
        Intent test=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.org/")).addCategory(Intent.CATEGORY_BROWSABLE);
        android.content.pm.ResolveInfo x=getPackageManager().resolveActivity(test,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        return x!=null && x.activityInfo!=null&&getPackageName().equals(x.activityInfo.packageName);
    }
    private void refresh(){
        if(screen==null)return;
        repaint=true;
        try{
            boolean l=Prefs.get(this,Prefs.LINKS), browser=isBrowser(), n=Prefs.get(this,Prefs.NOTICES), d=Prefs.get(this,Prefs.DNS), s=Prefs.get(this,Prefs.SECURE);
            boolean connected=AdNotificationService.isConnected(), access=AdNotificationService.hasAccess(this), running=AdDnsService.isRunning();
            int rules=NotificationRulesActivity.ruleCount(this);
            show(links,l,l?(browser?"正在保护外部网页链接":"可在打开方式中选择，尚未设为默认"):"已关闭");
            String error=AdDnsService.lastError(this);
            show(dns,d,running?"已连接本地 DNS 过滤":(error!=null&&!error.isEmpty()?error:(d?"正在建立连接":"已关闭")));
            show(notices,n,!n?"已关闭":(!access?"等待系统授予通知访问":(rules==0?"尚未选择要过滤的应用":(connected?"监听已连接 · 已选 "+rules+" 个应用":"等待系统连接监听服务"))));
            show(screen,s,s?"本应用窗口已保护":"已关闭");
            int count=(l&&browser?1:0)+(running?1:0)+(n&&access&&connected&&rules>0?1:0)+(s?1:0);
            if(managed!=null){DevicePolicyManager dp=getSystemService(DevicePolicyManager.class);boolean en=dp.getScreenCaptureDisabled(new ComponentName(this,PolicyReceiver.class));show(managed,en,en?"策略已生效":"策略已关闭");if(en)count++;}
            summary.setText(count==0?"当前没有生效的保护":"当前生效的保护 · "+count+" 项");
        } finally {repaint=false;}
    }
    private void browserSettings(){
        try{
            if(Build.VERSION.SDK_INT>=29){RoleManager role=getSystemService(RoleManager.class);
                if(role!=null&&role.isRoleAvailable(RoleManager.ROLE_BROWSER)&&!role.isRoleHeld(RoleManager.ROLE_BROWSER)){
                    startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_BROWSER),BROWSER_REQUEST);return;}}
            systemSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        }catch(ActivityNotFoundException|SecurityException ex){systemSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);}
    }
    private void notificationSettings(){
        if(Build.VERSION.SDK_INT>=30)try{
            Intent detail=new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            detail.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,new ComponentName(this,AdNotificationService.class).flattenToString());
            startActivity(detail);return;
        }catch(ActivityNotFoundException|SecurityException ignored){}
        systemSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }
    private void systemSettings(String action){
        try{startActivity(new Intent(action));}
        catch(ActivityNotFoundException|SecurityException ex){
            try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(ActivityNotFoundException nested){info("系统设置不可用","请从系统设置中打开对应选项。");}
        }
    }
    private void toggleDns(boolean on){
        if(!on){AdDnsService.stop(this);return;}
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        NetworkCapabilities cap=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
        if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&!AdDnsService.isRunning()){
            new GuardDialog(this).setTitle("切换当前 VPN？").setMessage("Android 同一用户只能连接一个 VPN。开启广告域名拦截会替换现有 VPN。")
                .setNegativeButton("保留当前 VPN",null).setPositiveButton("继续", (d,w)->prepareDns()).show();return;
        }
        prepareDns();
    }
    private void prepareDns(){
        try{Intent prep=VpnService.prepare(this);if(prep==null)AdDnsService.start(this);else startActivityForResult(prep,VPN_REQUEST);}
        catch(ActivityNotFoundException|SecurityException ex){info("无法连接","系统没有提供 VPN 授权页面。");}
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==VPN_REQUEST && res==RESULT_OK)AdDnsService.start(this);
        refresh();
    }
    private void domainEditor(){
        LinearLayout form=u.col();u.pad(form,22,6);
        form.addView(u.text("每行一个域名，包含其子域名。保存后同时用于链接确认和 DNS 拦截。",14,GuardUi.MUTED,false));
        EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(4);e.setMaxLines(8);e.setGravity(Gravity.TOP|Gravity.START);e.setHint("ads.example.org");e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32768)});
        e.setText(Prefs.prefs(this).getString(Prefs.CUSTOM,""));form.addView(e);
        AlertDialog dialog=new GuardDialog(this).setTitle("自定义广告域名").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            LinkedHashSet<String> hosts=new LinkedHashSet<>();int index=0;
            for(String line:e.getText().toString().split("\n",-1)){index++;String s=line.trim();if(s.isEmpty())continue;String host=GuardRules.normalizeRule(s);
                if(host.isEmpty()){e.setError("第 "+index+" 行需要填写域名");return;}hosts.add(host);}
            Prefs.prefs(this).edit().putString(Prefs.CUSTOM,android.text.TextUtils.join("\n",hosts)).apply();dialog.dismiss();
        }));dialog.show();
    }
    private void info(String title,String msg){new GuardDialog(this).setTitle(title).setMessage(msg).setPositiveButton("知道了",null).show();}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(tick);tick.run();}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
}
