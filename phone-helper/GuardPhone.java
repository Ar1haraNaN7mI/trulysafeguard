import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Local, shell-authorized executor compiled into the installed signed APK. No network or command IPC. */
public final class GuardPhone {
    private static final String APP="app.local.privacyguard", PROVIDER="content://app.local.privacyguard.usb/phone";
    private static final File DIR=new File("/data/local/tmp/privacyguard-rules-v1");
    private static final String[] FIELDS={"sensors","notifications","screen","sources"};
    private static final String PKG="[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+";
    private static final int B64=Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING;
    private static final class Invalid extends Exception { Invalid(String msg){super(msg);} }
    static final class Result {
        final int code; final String out,err;
        Result(int c,String o,String e){code=c;out=o;err=e;}
    }
    private static final class Drain extends Thread {
        final InputStream stream; final ByteArrayOutputStream data=new ByteArrayOutputStream();
        boolean tooLarge;
        Drain(InputStream s){stream=s;}
        public void run(){try(InputStream in=stream){byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){if(data.size()+n<=8*1024*1024)data.write(b,0,n);else tooLarge=true;}
        }catch(IOException ex){tooLarge=true;}}
        String value(){return new String(data.toByteArray(),StandardCharsets.UTF_8).replace("\r","").trim();}
    }
    static Result raw(String... args) throws Exception {
        String tool=args[0];
        if(!Arrays.asList("cmd","pm","am","dumpsys","settings","content","getprop","cat").contains(tool))
            throw new Invalid("未支持的系统操作");
        if(tool.equals("cat")&&(args.length!=2||!args[1].equals("/proc/sys/kernel/random/boot_id")))
            throw new Invalid("未支持的系统读取");
        String[] argv=args.clone();argv[0]="/system/bin/"+tool;
        Process p=new ProcessBuilder(argv).start();
        Drain out=new Drain(p.getInputStream()),err=new Drain(p.getErrorStream());
        out.start();err.start();p.getOutputStream().close();
        boolean done=p.waitFor(12,TimeUnit.SECONDS);
        if(!done)p.destroyForcibly();
        out.join(1800);err.join(1800);
        if(!done||out.isAlive()||err.isAlive()||out.tooLarge||err.tooLarge)throw new Invalid("系统服务超时或输出超限");
        return new Result(p.exitValue(),out.value(),err.value());
    }
    static String cmd(String... args) throws Exception {
        Result r=raw(args);
        if(r.code!=0 || !r.err.isEmpty() || r.out.startsWith("Error") || r.out.startsWith("Exception"))
            throw new Invalid("系统未接受操作（"+args[0]+"）");
        return r.out;
    }
    private final AtomicFile disk=new AtomicFile(new File(DIR,"restore.json"));
    private JSONObject journal;
    private GuardPhone() throws Exception {
        try{journal=new JSONObject(new String(disk.readFully(),StandardCharsets.UTF_8));}
        catch(FileNotFoundException missing){journal=new JSONObject();}
    }
    private void save() {
        FileOutputStream out=null;
        try{out=disk.startWrite();out.write(journal.toString().getBytes(StandardCharsets.UTF_8));disk.finishWrite(out);}
        catch(Exception e){if(out!=null)disk.failWrite(out);throw new IllegalStateException("无法保存恢复记录",e);}
    }
    private static Map<String,Integer> packages(boolean third) throws Exception {
        ArrayList<String> args=new ArrayList<>(Arrays.asList("cmd","package","list","packages","-U","--user","0"));
        if(third)args.add("-3");
        Map<String,Integer> map=new HashMap<>();Pattern row=Pattern.compile("package:(\\S+)\\s+uid:(\\d+)");
        for(String line:cmd(args.toArray(new String[0])).split("\n")){
            Matcher m=row.matcher(line.trim());if(!m.matches()||map.put(m.group(1),Integer.parseInt(m.group(2)))!=null)
                throw new Invalid("无法验证应用 UID 列表");
        }
        return map;
    }
    private static Map<String,Integer> eligible(boolean restoring) throws Exception {
        Map<String,Integer> all=packages(false),third=packages(true);
        if(!all.containsKey("android")||(!restoring&&!all.containsKey(APP)))throw new Invalid("系统应用清单不完整");
        Map<Integer,Integer> counts=new HashMap<>();
        for(int n:all.values())counts.put(n,counts.getOrDefault(n,0)+1);
        third.entrySet().removeIf(entry->{int uid=entry.getValue();return uid<10000||uid>19999||
                entry.getKey().equals(APP)||!entry.getValue().equals(all.get(entry.getKey()))||counts.get(uid)!=1;});
        return third;
    }
    private static String path(String pkg) throws Exception {
        String[] found=cmd("pm","path","--user","0",pkg).split("\n");
        if(found.length==0||!found[0].startsWith("package:/data/app/")||!found[0].endsWith("/base.apk"))
            throw new Invalid("应用安装路径无法核实");
        return found[0].substring(8);
    }
    private static JSONObject rules() throws Exception {
        String row=cmd("content","query","--user","0","--uri",PROVIDER,"--projection","payload");
        if(!row.matches("Row: 0 payload=[A-Za-z0-9_-]{1,180000}"))throw new Invalid("无法读取本机规则");
        String payload=row.substring(row.indexOf('=')+1);
        JSONObject body=new JSONObject(new String(Base64.decode(payload,B64),StandardCharsets.UTF_8));
        if(body.getInt("v")!=2 || body.getJSONArray("rules").length()>128)throw new Invalid("规则版本或数量不匹配");
        return body;
    }
    private static void publish(String state,JSONArray rows,String assistId,String assistResult) throws Exception {
        JSONObject body=new JSONObject().put("v",1).put("time",System.currentTimeMillis()/1000)
                .put("state",state).put("status",rows);
        if(!assistId.isEmpty())body.put("assistId",assistId).put("assistResult",assistResult);
        String payload=Base64.encodeToString(body.toString().getBytes(StandardCharsets.UTF_8),B64);
        // /system/bin/content is a separate process; limit each Binder and argv payload.
        if(payload.length()>72000 && rows.length()>1){
            JSONArray left=new JSONArray(),right=new JSONArray();
            for(int i=0;i<rows.length();i++)(i<rows.length()/2?left:right).put(rows.getJSONObject(i));
            publish(state,left,assistId,assistResult);publish(state,right,assistId,assistResult);return;
        }
        String ack=cmd("content","call","--user","0","--uri",PROVIDER,"--method","phoneStatus","--arg",payload);
        if(!ack.startsWith("Result: Bundle[") || !ack.contains("ok=true"))throw new Invalid("手机未确认状态");
    }
    private static String concise(Exception e) {
        String msg=e.getMessage();
        if(msg==null||msg.length()>140||msg.indexOf('\n')>=0)return "系统操作未确认";
        return msg;
    }
    private static JSONObject blank(String pkg,String msg)throws Exception{
        JSONObject row=new JSONObject().put("pkg",pkg);for(String f:FIELDS)row.put(f,msg);return row;
    }
    private JSONArray apply(JSONArray requested,boolean enabled)throws Exception{
        JSONArray reports=new JSONArray();
        if(requested.length()==0&&journal.length()==0)return reports;
        Map<String,Integer> allowed=eligible(!enabled&&requested.length()==0);Map<String,JSONObject> needed=new LinkedHashMap<>();
        boolean watch=false;
        for(int i=0;i<requested.length();i++){
            JSONObject rule=requested.getJSONObject(i);String pkg=rule.getString("pkg");
            if(!pkg.matches(PKG)||pkg.length()>255||needed.put(pkg,rule)!=null)throw new Invalid("规则来源无效");
            if(enabled&&rule.getBoolean("screen"))watch=true;
        }
        String projection="",projectionError="";
        if(watch){try{projection=ScreenPolicy.current();}catch(Exception e){projectionError=concise(e);}}
        Set<String> targets=new LinkedHashSet<>(needed.keySet());journal.keys().forEachRemaining(targets::add);
        for(String pkg:targets){
            JSONObject rule=needed.get(pkg), prior=journal.optJSONObject(pkg);
            if(!pkg.matches(PKG)||!allowed.containsKey(pkg)){
                if(rule!=null)reports.put(blank(pkg,"已跳过系统、共享 UID 或已移除的应用"));
                continue;
            }
            int uid=allowed.get(pkg);String id=rule==null?"":rule.getString("id");
            if(rule!=null&&(!id.matches(uid+"/[0-9]{1,16}"))){reports.put(blank(pkg,"安装身份已改变"));continue;}
            if(prior!=null && rule!=null && !id.equals(prior.optString("id"))){
                journal.remove(pkg);save();prior=null;
            }
            if(prior==null&&rule!=null&&(!enabled || !(rule.getBoolean("sensors")||rule.getBoolean("notifications")
                    ||rule.getBoolean("sources")||rule.getBoolean("screen")))){reports.put(blank(pkg,"关闭"));continue;}
            // Orphan recovery is allowed only for the exact, still-installed APK last managed.
            if(rule==null&&(prior==null||!prior.optString("path").equals(path(pkg))||prior.optInt("uid")!=uid))
                continue;
            JSONObject entry=prior!=null?prior:new JSONObject().put("id",id).put("uid",uid).put("path",path(pkg));
            JSONObject row=new JSONObject().put("pkg",pkg);
            boolean run=enabled&&rule!=null;
            final Runnable persist=()->{try{journal.put(pkg,entry);save();}catch(Exception e){throw new IllegalStateException(e);}};
            for(String field:FIELDS) {
                boolean on=run&&rule.getBoolean(field);
                try{
                    String status;
                    if(field.equals("screen"))status=!on?"关闭":(!projectionError.isEmpty()?projectionError:ScreenPolicy.enforce(pkg,uid,projection));
                    else if(field.equals("sensors"))status=PolicyOps.sensors(pkg,on,entry,persist);
                    else if(field.equals("notifications"))status=PolicyOps.notifications(pkg,on,entry,persist);
                    else if(on&&!rule.getBoolean("declaresInstall"))status="该应用没有声明来源安装权限";
                    else status=PolicyOps.sources(pkg,on,entry,persist);
                    row.put(field,status);
                }catch(Exception e){row.put(field,"未确认 · "+concise(e));}
            }
            if(entry.length()==3 && journal.has(pkg)){journal.remove(pkg);save();}
            if(rule!=null)reports.put(row);
        }
        return reports;
    }
    private void loop(String original) throws Exception{
        int failures=0;
        AssistSettings assist=new AssistSettings(DIR);
        while(true){
            if(!original.equals(path(APP))){apply(new JSONArray(),false);return;}
            try{
                if(!"0".equals(cmd("am","get-current-user"))){SystemClock.sleep(5000);continue;}
                JSONObject policy=rules();String mode=policy.getString("mode");
                if(!Arrays.asList("run","pause","exit").contains(mode))mode="exit";
                JSONArray list=policy.getJSONArray("rules");
                JSONArray rows=apply(list,"run".equals(mode));
                boolean pending=journal.length()!=0 && !"run".equals(mode);
                String state="run".equals(mode)?"运行中":("pause".equals(mode)?"已暂停":"已结束");
                if(pending)state="部分设置待恢复 · 请查看各应用回报";
                String assistId=policy.optString("assistId","");
                String assistResult="";
                if("run".equals(mode) && assistId.matches("[a-f0-9-]{36}")){
                    try { assistResult=assist.apply(policy.getString("assistAction")); }
                    catch (Exception ex) { assistResult="未完成 · "+concise(ex); }
                }else assistId="";
                publish(state,rows,assistId,assistResult);failures=0;
                if("exit".equals(mode))return;
                SystemClock.sleep(list.length()==0?5500:2200);
            }catch(Exception ex){
                if(++failures>=4){try{apply(new JSONArray(),false);}catch(Exception ignored){}throw ex;}
                SystemClock.sleep(1500);
            }
        }
    }
    public static void main(String[] args){
        if(android.os.Process.myUid()!=2000 || args.length!=0)return;
        try(RandomAccessFile single=new RandomAccessFile(new File(DIR,"running.lock"),"rw");
            FileLock lock=single.getChannel().tryLock()){
            if(lock==null)return;
            String original=System.getenv("CLASSPATH");
            if(original==null || !original.equals(path(APP)))return;
            GuardPhone runner=new GuardPhone();
            try{runner.loop(original);}
            finally{try{runner.apply(new JSONArray(),false);}catch(Exception ignored){}}
        }catch(Exception e){System.err.println("GuardPhone: "+concise(e));}
    }
}
