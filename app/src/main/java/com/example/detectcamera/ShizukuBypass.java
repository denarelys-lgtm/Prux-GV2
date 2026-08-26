package com.example.detectcamera;

import android.content.Context;
import android.util.Log;

import rikka.shizuku.Shizuku;

public class ShizukuBypass {

    private static final String TAG = "ShizukuBypass";

    public static void aplicarExencionesBackground(Context context) {
        try {
            if (Shizuku.pingBinder()) {
                String pkg = context.getPackageName();
                
                // Exenciones de AppOps para permitir FGS e inicio en background
                ejecutarComandoShizuku("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow");
                ejecutarComandoShizuku("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");
                ejecutarComandoShizuku("cmd appops set " + pkg + " START_FOREGROUND allow");
                
                // Exención de optimización de batería por Shell
                ejecutarComandoShizuku("dumpsys deviceidle whitelist +" + pkg);
                
                Log.i(TAG, "Exenciones de background aplicadas exitosamente vía Shizuku.");
            } else {
                Log.w(TAG, "Shizuku no está disponible para aplicar las exenciones.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error aplicando exenciones con Shizuku: ", e);
        }
    }

    private static void ejecutarComandoShizuku(String comando) {
        try {
            Shizuku.newProcess(new String[]{"sh", "-c", comando}, null, null);
        } catch (Exception e) {
            Log.e(TAG, "Error ejecutando comando en Shizuku: " + comando, e);
        }
    }
}

