package com.example.reviewreminder;
import android.app.Activity;
import android.os.Bundle;
import android.content.ComponentName;
public final class QaLauncher extends Activity {
    public void onCreate(Bundle state){super.onCreate(state);android.widget.TextView label=new android.widget.TextView(this);label.setText("Native QA: start with adb shell am instrument -w");setContentView(label);}
}
