package com.example.detectcamera;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.OutputStream;

import rikka.shizuku.Shizuku;

public class MediaProjectionHelper {

    private static final String TAG = "MediaProjectionHelper";

    public static boolean isShizukuAvailable() {
        try {
            boolean ping = Shizuku.pingBinder();
            int perm = Shizuku.checkSelfPermission();
            boolean granted = (perm == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "Shizuku ping=" + ping + ", perm=" + perm + ", granted=" + granted);
            return ping && granted;
        } catch (Exception e) {
            Log.e(TAG, "Error checking Shizuku", e);
            return false;
        }
    }

    public static boolean otorgarConsentimientoShizuku(String packageName) {
        String cmd1 = "appops set " + packageName + " PROJECT_MEDIA allow";
        String cmd2 = "pm grant " + packageName + " android.permission.PROJECT_MEDIA";
        String cmd3 = "appops set " + packageName + " SYSTEM_ALERT_WINDOW allow";
        return ejecutarComandoShell(cmd1) && ejecutarComandoShell(cmd2) && ejecutarComandoShell(cmd3);
    }

    public static boolean ejecutarComandoShell(String command) {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku no disponible para ejecutar: " + command);
            return false;
        }

        try {
            Process process = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream os = process.getOutputStream();
            os.write((command + "\n").getBytes());
            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Log.i(TAG, "Comando ejecutado: " + command);
                return true;
            } else {
                Log.e(TAG, "Comando falló: " + command + " (código " + exitCode + ")");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error ejecutando comando: " + command, e);
            return false;
        }
    }
}
