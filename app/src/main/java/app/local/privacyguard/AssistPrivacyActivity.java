package app.local.privacyguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.UUID;

/** User-facing control for the system assistant's screenshot and structure requests. */
public final class AssistPrivacyActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){refresh();handler.postDelayed(this,1600);}};
    private SharedPreferences prefs;
    private TextView values, feedback;
    private Button block, restore;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        setTitle("数字助理的屏幕读取");
        prefs=UsbPolicyProvider.prefs(this);
        GuardUi ui=new GuardUi(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout page=ui.col();ui.pad(page,20,18);page.setBackgroundColor(GuardUi.BG);
        scroll.addView(page);setContentView(scroll);
        ui.add(page,ui.action("返回",v->finish()),0);
        ui.add(page,ui.text("数字助理的屏幕读取",25,GuardUi.INK,true),4);
        ui.add(page,ui.text("调用标准数字助理时，系统可以为它请求当前应用的截图和界面结构。禁止后，两项请求会在获取数据前被过滤，按键截图仍可使用。",16,GuardUi.INK,false),10);
        values=ui.text("",16,GuardUi.TEAL,true);ui.add(page,values,14);
        block=ui.action("禁止助理读取截图和界面结构",v->request("block"));ui.add(page,block,8);
        restore=ui.action("恢复助理的原有设置",v->request("restore"));ui.add(page,restore,2);
        feedback=ui.text("",14,GuardUi.MUTED,false);ui.add(page,feedback,8);
        ui.add(page,ui.action("查看手机规则助手",v->startActivity(new Intent(this,UsbPolicyActivity.class))),4);
        ui.add(page,ui.text("修改由已激活的手机规则助手执行。写入后由系统保存，退出助手或拔线也会保留。要撤销请先在本页恢复。作用范围是当前用户的标准数字助理通道，其他录屏接口、无障碍服务和厂商自有取屏接口另有权限。",14,GuardUi.MUTED,false),12);
    }
    private String read(String name){
        try {
            String value=Settings.Secure.getString(getContentResolver(),name);
            if(value==null)return "系统默认";
            if("0".equals(value))return "禁止";
            if("1".equals(value))return "允许";
        }catch(RuntimeException unavailable){ /* Display unreadable separately. */ }
        return "无法从当前应用读取";
    }
    private void request(String action){
        if(prefs.contains("assistId"))return;
        prefs.edit().putString("assistAction",action).putString("assistId",UUID.randomUUID().toString())
                .remove("assistResult").apply();
        refresh();
    }
    private void refresh(){
        values.setText("助理截图 · "+read("assist_screenshot_enabled")+"\n界面结构 · "+read("assist_structure_enabled"));
        long age=UsbPolicyProvider.phoneAge(prefs);
        boolean available=age>=0 && age<45000 && "run".equals(prefs.getString(UsbPolicyProvider.PHONE_MODE,""))
                && prefs.getString(UsbPolicyProvider.PHONE_STATE,"").startsWith("运行中");
        boolean pending=prefs.contains("assistId");
        block.setEnabled(available&&!pending);restore.setEnabled(available&&!pending);
        String last=prefs.getString("assistResult","");
        feedback.setText(pending?"等待手机规则助手执行":(available?"手机规则助手在线":"修改设置前需激活手机规则助手")
                +(last.isEmpty()?"":"\n上次操作 · "+last));
    }
    @Override protected void onResume(){super.onResume();poll.run();}
    @Override protected void onPause(){handler.removeCallbacks(poll);super.onPause();}
}
