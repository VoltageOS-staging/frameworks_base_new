package android.ext;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** @hide */
public class SettingsIntents {

    public static final String APP_MANAGE_PLAY_INTEGRITY_API = "android.settings.OPEN_APP_MANAGE_PLAY_INTEGRITY_API_SETTINGS";

    public static Intent getAppIntent(Context ctx, String action, String pkgName) {
        var i = new Intent(action);
        i.setData(Uri.fromParts("package", pkgName, null));
        i.setPackage(KnownSystemPackages.get(ctx).settings);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return i;
    }
}
