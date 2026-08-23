package com.example.detectcamera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public class ProjectionActivity extends Activity {

    private static final int REQUEST_CODE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (manager != null) {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CODE);
        } else {
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, CameraService.class);
                serviceIntent.setAction("ACTION_START_PROJECTION");
                serviceIntent.putExtra("EXTRA_RESULT_CODE", resultCode);
                serviceIntent.putExtra("EXTRA_DATA", data);
                startService(serviceIntent);
            }
        }
        finish();
    }
}
