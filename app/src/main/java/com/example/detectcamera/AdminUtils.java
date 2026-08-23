package com.example.detectcamera;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public class AdminUtils {

    private static final String TAG = "AdminUtils";

    public static void otorgarPermisosSilenciosamente(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(context, AdminReceiver.class);

        if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
            String[] permisos = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            };

            for (String permiso : permisos) {
                try {
                    dpm.setPermissionGrantState(
                            adminComponent,
                            context.getPackageName(),
                            permiso,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Error otorgando permiso " + permiso + ": " + e.getMessage());
                }
            }
        }
    }
}
