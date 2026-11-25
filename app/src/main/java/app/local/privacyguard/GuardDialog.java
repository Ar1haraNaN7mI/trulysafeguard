package app.local.privacyguard;

import android.app.AlertDialog;
import android.content.Context;

/** Dialogs have their own windows and need the same privacy policy as the activity. */
final class GuardDialog extends AlertDialog.Builder {
    GuardDialog(Context context) { super(context); }
    @Override public AlertDialog create() {
        AlertDialog result=super.create();
        if(result.getWindow()!=null) {
            GuardApp app=(GuardApp)getContext().getApplicationContext();
            app.registerDialogWindow(result.getWindow());
        }
        return result;
    }
}
