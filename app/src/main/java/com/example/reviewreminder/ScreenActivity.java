package com.example.reviewreminder;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

public class ScreenActivity extends Activity {
    private String appliedThemeSig = "";

    @Override public void onCreate(Bundle state) {
        Theme.apply(this);
        appliedThemeSig = Theme.signature(this);
        setTheme(Theme.DARK ? R.style.AppTheme_Dark : R.style.AppTheme);
        super.onCreate(state);
        // This Waydroid image crashes SurfaceFlinger when HWUI queries physical displays.
        if(!Build.DEVICE.toLowerCase(java.util.Locale.ROOT).contains("waydroid")) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        String currentSig = Theme.signature(this);
        if (!appliedThemeSig.equals(currentSig)) {
            Theme.apply(this);
            appliedThemeSig = currentSig;
            recreate();
            return;
        }
        Theme.apply(this);
    }

    @Override protected void onDestroy() {
        Sheet.dismissAllFor(this);
        super.onDestroy();
    }

    @Override public void finish(){super.finish();Motion.close(this);}
}
