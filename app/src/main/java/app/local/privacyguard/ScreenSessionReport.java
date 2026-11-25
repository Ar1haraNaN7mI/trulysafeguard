package app.local.privacyguard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A single fixed, read-only system-service query launched with the ordinary app UID. */
final class ScreenSessionReport {
    private static final String PKG="(?:android|[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)";
    private static final String TYPE="TYPE_(?:SCREEN_CAPTURE|MIRRORING|PRESENTATION)";
    private static final Pattern LEGACY=Pattern.compile("\\(("+PKG+"), uid=([0-9]{1,9})\\): "+TYPE);
    private static final Pattern NAME=Pattern.compile("packageName: ("+PKG+")");
    static final class Reading {
        final boolean confirmed;
        final String source,reason;
        Reading(boolean confirmed,String source,String reason){this.confirmed=confirmed;this.source=source;this.reason=reason;}
    }
    static Reading unavailable(String reason){return new Reading(false,"",reason);}
    static Reading parse(String raw){
        List<String> lines=new ArrayList<>();
        for(String line:raw.split("\n"))if(!line.trim().isEmpty())lines.add(line.trim());
        if(lines.size()<3 || !lines.get(0).equals("MEDIA PROJECTION MANAGER (dumpsys media_projection)") ||
                !lines.get(1).equals("Media Projection:") || Collections.frequency(lines,"Media Projection:")!=1)
            return unavailable("系统未向本应用返回可识别的会话报告");
        String record=lines.get(2);
        if(record.equals("null"))return new Reading(true,"","");
        Matcher old=LEGACY.matcher(record);
        if(old.matches())return new Reading(true,old.group(1),"");
        if(record.equals("Projection:") && lines.size()>=7 && Collections.frequency(lines,"Projection:")==1){
            Matcher name=NAME.matcher(lines.get(3));
            if(name.matches() && lines.get(4).matches("uid: [0-9]{1,9}") &&
                    lines.get(5).matches("userHandle: UserHandle\\{[0-9]+\\}") && lines.get(6).matches("type: "+TYPE))
                return new Reading(true,name.group(1),"");
        }
        return unavailable("系统会话格式未识别");
    }
    static Reading query(Context context){
        if(context.checkSelfPermission(android.Manifest.permission.DUMP)!=PackageManager.PERMISSION_GRANTED)
            return unavailable("尚未授予一次性配置的系统诊断权限");
        Process process=null;
        Handler watchdog=new Handler(Looper.getMainLooper());
        Runnable terminate=null;
        try{
            process=new ProcessBuilder("/system/bin/dumpsys","-t","2","media_projection").redirectErrorStream(true).start();
            final Process child=process;terminate=child::destroyForcibly;
            watchdog.postDelayed(terminate,3800);
            try(InputStream in=process.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[2048];int n;
                while((n=in.read(buf))!=-1){if(out.size()+n>131072)return unavailable("系统会话报告超过读取上限");out.write(buf,0,n);}
                if(!process.waitFor(1,TimeUnit.SECONDS)||process.exitValue()!=0)
                    return unavailable("系统拒绝了应用身份的会话查询或查询超时");
                return parse(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();return unavailable("查询已中断");}
        catch(Exception e){return unavailable("系统未允许本应用读取投屏会话");}
        finally{if(terminate!=null)watchdog.removeCallbacks(terminate);if(process!=null)process.destroyForcibly();}
    }
    private ScreenSessionReport(){}
}
