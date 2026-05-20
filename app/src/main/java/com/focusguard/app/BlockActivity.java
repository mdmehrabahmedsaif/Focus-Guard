package com.focusguard.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class BlockActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String mode = intent != null ? intent.getStringExtra("mode") : null;

        if ("temp_cover".equals(mode)) {
            // Disable all enter transitions for absolute zero latency
            overridePendingTransition(0, 0);

            // Programmatically set a lightweight full-screen black view (no XML inflation overhead)
            View coverView = new View(this);
            coverView.setBackgroundColor(Color.BLACK);
            setContentView(coverView);

            // Dismiss overlay after 350ms once the background transition completes
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finish();
                overridePendingTransition(0, 0);
            }, 350);

            return;
        }

        setContentView(R.layout.activity_block);

        Button btnGoBack = findViewById(R.id.btnGoBack);
        btnGoBack.setOnClickListener(v -> {
            // Send user to Home screen
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // Prevent back button from working inside the block screen
    }
}
