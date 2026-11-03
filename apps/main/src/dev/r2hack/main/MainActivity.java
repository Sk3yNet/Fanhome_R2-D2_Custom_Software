package dev.r2hack.main;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;
import android.widget.TextView;

/** HDMI status screen. The real work happens in BrainService. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler();
    private TextView view;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            view.setText(BrainService.statusText());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new TextView(this);
        view.setTextColor(Color.GREEN);
        view.setBackgroundColor(Color.BLACK);
        view.setTextSize(14);
        view.setPadding(24, 24, 24, 24);
        setContentView(view);
        startService(new Intent(this, BrainService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
    }
}
