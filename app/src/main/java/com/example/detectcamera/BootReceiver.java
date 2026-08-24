package com.example.detectcamera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/** Arranque mínimo y seguro: deja el servidor disponible después del reinicio. */
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
            Intent serviceIntent = new Intent(context, ServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "ServerService solicitado después del arranque: " + action);
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo iniciar ServerService después del arranque", t);
        }
    }
}
