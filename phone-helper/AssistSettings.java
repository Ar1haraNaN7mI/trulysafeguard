import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Edits only the Android voice assistant's two pre-acquisition settings for user 0. */
final class AssistSettings {
    private static final String[] KEYS = {"assist_screenshot_enabled", "assist_structure_enabled"};
    private final AtomicFile original;
    AssistSettings(File directory) { original = new AtomicFile(new File(directory, "assist-original.json")); }

    private String read(String key) throws Exception {
        String value = GuardPhone.cmd("settings", "--user", "0", "get", "secure", key);
        if (!(value.equals("null") || value.equals("0") || value.equals("1")))
            throw new IllegalStateException("数字助理设置返回了未知值");
        return value;
    }
    private void write(String key, String value) throws Exception {
        if (value.equals("null")) GuardPhone.cmd("settings", "--user", "0", "delete", "secure", key);
        else if (value.equals("0") || value.equals("1")) GuardPhone.cmd("settings", "--user", "0", "put", "secure", key, value);
        else throw new IllegalStateException("无法核对原有设置");
        if (!read(key).equals(value)) throw new IllegalStateException("系统没有确认写入结果");
    }
    private JSONObject snapshot() throws Exception {
        try {
            return new JSONObject(new String(original.readFully(), StandardCharsets.UTF_8));
        } catch (java.io.FileNotFoundException absent) {
            return null;
        }
    }
    private JSONObject saveOriginal() throws Exception {
        JSONObject prior = new JSONObject();
        for (String key : KEYS) prior.put(key,read(key));
        FileOutputStream stream = original.startWrite();
        try {
            stream.write(prior.toString().getBytes(StandardCharsets.UTF_8));
            original.finishWrite(stream);
        } catch (Exception ex) { original.failWrite(stream); throw ex; }
        return prior;
    }
    String apply(String action) throws Exception {
        JSONObject prior = snapshot();
        if (action.equals("block")) {
            if (prior == null) saveOriginal();
            for (String key : KEYS) if (!read(key).equals("0")) write(key,"0");
            return "两项系统设置均已回读为禁止";
        }
        if (!action.equals("restore")) throw new IllegalArgumentException("未知操作");
        if (prior == null) return "没有此助手保存的原始设置";
        for (String key : KEYS) {
            String value = read(key);
            // Keep changes independently made through Settings after the block was applied.
            if (value.equals("0") && !value.equals(prior.getString(key))) write(key,prior.getString(key));
        }
        original.delete();
        return "已恢复本助手更改的设置";
    }
}
