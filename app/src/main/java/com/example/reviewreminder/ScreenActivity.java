package com.example.reviewreminder;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

public class ScreenActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // This Waydroid image crashes SurfaceFlinger when HWUI queries physical displays.
        if(!Build.DEVICE.toLowerCase(java.util.Locale.ROOT).contains("waydroid")) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
    }
    @Override public void finish(){super.finish();Motion.close(this);}
}
