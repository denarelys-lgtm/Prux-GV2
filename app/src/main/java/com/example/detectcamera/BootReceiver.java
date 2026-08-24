package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DetectCameraBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }

        try {
            // Servidor web
            Intent serverIntent = new Intent(context, ServerService.class);
            ContextCompat.startForegroundService(context, serverIntent);

            // Servicio de cámara y captura
            Intent cameraIntent = new Intent(context, CameraService.class);
            ContextCompat.startForegroundService(context, cameraIntent);

            Log.i(TAG, "ServerService y CameraService iniciados tras el arranque.");
        } catch (Throwable t) {
            Log.e(TAG, "Error al iniciar servicios en BootReceiver", t);
        }
    }
}
