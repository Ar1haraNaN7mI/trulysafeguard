package app.local.privacyguard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SecureViewerActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int PICK=30, TEXT_LIMIT=1024*1024, IMAGE_LIMIT=16*1024*1024;
    private final ExecutorService reader=Executors.newSingleThreadExecutor();
    private int generation;
    private GuardUi u;
    private LinearLayout body;
    private TextView status;
    private Button enableScreenProtection;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);GuardApp.protect(this);u=new GuardUi(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(GuardUi.BG);
        LinearLayout page=u.col();u.pad(page,20,20);scroll.addView(page);setContentView(scroll);
        page.addView(u.action("‹  返回守护面板",v->finish()));page.addView(u.text("私密文件预览",26,GuardUi.INK,true));
        status=u.text("",14,GuardUi.MUTED,false);u.add(page,status,6);
        page.addView(u.action("选择图片或文本",v->pick()));body=u.col();u.add(page,body,10);
        enableScreenProtection=u.action("开启本应用防截屏",v->Prefs.set(this,Prefs.SECURE,true));
        page.addView(enableScreenProtection,3);
        updateStatus();
    }
    @Override protected void onStart() {
        super.onStart();
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this);
        updateStatus();
    }
    @Override protected void onStop() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onStop();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key) {
        if (key==null || Prefs.SECURE.equals(key)) runOnUiThread(this::updateStatus);
    }
    private void updateStatus(){
        boolean enabled=Prefs.get(this,Prefs.SECURE);
        enableScreenProtection.setVisibility(enabled?View.GONE:View.VISIBLE);
        String scope=enabled?"防截屏已开启。":"当前窗口允许截屏。";
        status.setText(scope+"支持图片（16 MB 内）和 UTF-8 文本（1 MB 内）。退出会清除预览。");
    }
    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","text/*"});
        try{startActivityForResult(i,PICK);}catch(android.content.ActivityNotFoundException e){message("系统文件选择器不可用。");}
    }
    private void message(String s){body.removeAllViews();body.addView(u.text(s,16,GuardUi.MUTED,false));}
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req!=PICK||res!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();if(!"content".equals(uri.getScheme())){message("请选择系统文件选择器中的文件。");return;}
        final int token=++generation;message("正在读取文件…");
        reader.execute(()->{
            try{
                String type=getContentResolver().getType(uri);boolean image=type!=null&&type.startsWith("image/");
                if(!image&&(type==null||!type.startsWith("text/")))throw new IOException("不支持此文件类型。");
                byte[] bytes=load(uri,image?IMAGE_LIMIT:TEXT_LIMIT);
                if(image){
                    BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;
                    BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
                    if(opt.outWidth<=0||opt.outHeight<=0)throw new IOException("无法解码这张图片。");
                    int sample=1;while(Math.max(opt.outWidth,opt.outHeight)/sample>2048)sample*=2;
                    opt.inSampleSize=sample;opt.inJustDecodeBounds=false;
                    Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
                    if(bitmap==null)throw new IOException("无法解码这张图片。");
                    runOnUiThread(()->{if(!current(token)){bitmap.recycle();return;}body.removeAllViews();
                        ImageView v=new ImageView(this);v.setAdjustViewBounds(true);v.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        v.setContentDescription("所选图片");v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setImageBitmap(bitmap);body.addView(v,new LinearLayout.LayoutParams(-1,-2));});
                }else{
                    String txt=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                    final String value=txt.startsWith("\uFEFF")?txt.substring(1):txt;
                    runOnUiThread(()->{if(current(token))showText(value,0);});
                }
            }catch(CharacterCodingException e){fail(token,"只支持 UTF-8 编码文本。");}
            catch(IOException e){fail(token,e.getMessage()!=null&&e.getMessage().startsWith("文件超过")?e.getMessage():"无法读取文件，请确认格式并重新选择。");}
            catch(SecurityException|IllegalArgumentException e){fail(token,"文件读取权限已失效，请重新选择。");}
        });
    }
    private static final int PAGE_CHARS=3200;
    private static int boundary(String value,int offset){
        int p=Math.min(value.length(),offset);
        if(p>0&&p<value.length()&&Character.isLowSurrogate(value.charAt(p))&&Character.isHighSurrogate(value.charAt(p-1)))p--;
        return p;
    }
    private void showText(String value,int index){
        body.removeAllViews();
        int pages=Math.max(1,(value.length()+PAGE_CHARS-1)/PAGE_CHARS);
        if(pages>1){
            LinearLayout nav=new LinearLayout(this);nav.setGravity(android.view.Gravity.CENTER_VERTICAL);
            Button prev=u.action("上一页",v->showText(value,index-1));prev.setEnabled(index>0);nav.addView(prev);
            TextView count=u.text((index+1)+" / "+pages,14,GuardUi.MUTED,false);count.setGravity(android.view.Gravity.CENTER);nav.addView(count,new LinearLayout.LayoutParams(0,-2,1));
            Button next=u.action("下一页",v->showText(value,index+1));next.setEnabled(index+1<pages);nav.addView(next);body.addView(nav);
        }
        body.addView(new PrivatePage(value.substring(boundary(value,index*PAGE_CHARS),boundary(value,(index+1)*PAGE_CHARS))),new LinearLayout.LayoutParams(-1,-2));
        if(body.getParent() instanceof View && ((View)body.getParent()).getParent() instanceof ScrollView)
            ((ScrollView)((View)body.getParent()).getParent()).scrollTo(0,0);
    }
    // Draw text without publishing document contents to Android's normal view/accessibility tree.
    private final class PrivatePage extends View {
        private final String value;
        private final TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout layout;
        PrivatePage(String contents){
            super(SecureViewerActivity.this);value=contents;paint.setColor(GuardUi.INK);
            paint.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,17,getResources().getDisplayMetrics()));
            setSaveEnabled(false);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            if(android.os.Build.VERSION.SDK_INT>=29)setImportantForContentCapture(IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int w=Math.max(1,MeasureSpec.getSize(widthSpec));
            if(layout==null||layout.getWidth()!=w)layout=StaticLayout.Builder.obtain(value,0,value.length(),paint,w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setLineSpacing(u.dp(3),1).build();
            setMeasuredDimension(w,resolveSize(layout.getHeight(),heightSpec));
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(layout!=null)layout.draw(canvas);}
    }
    private boolean current(int token){return token==generation&&!isFinishing()&&!isDestroyed();}
    private void fail(int token,String msg){runOnUiThread(()->{if(current(token))message(msg);});}
    private byte[] load(Uri uri,int max) throws IOException{
        try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException();
            byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException();
                if(out.size()+n>max)throw new IOException("文件超过预览上限。");out.write(buf,0,n);}
            return out.toByteArray();
        }
    }
    @Override protected void onDestroy(){generation++;reader.shutdownNow();if(body!=null)body.removeAllViews();super.onDestroy();}
}
