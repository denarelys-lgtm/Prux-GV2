package com.example.detectcamera;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.OutputStream;

import rikka.shizuku.Shizuku;

public class MediaProjectionHelper {

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Otorga los permisos necesarios para la captura de pantalla usando Shizuku.
     * Esto evita que el sistema pida el permiso PROJECT_MEDIA, pero aún se necesita
     * la confirmación del usuario para el selector de pantalla.
     */
    public static boolean otorgarConsentimientoShizuku(String packageName) {
        String cmd1 = "appops set " + packageName + " PROJECT_MEDIA allow";
        String cmd2 = "pm grant " + packageName + " android.permission.PROJECT_MEDIA";
        // Opcional: también conceder SYSTEM_ALERT_WINDOW si es necesario
        String cmd3 = "appops set " + packageName + " SYSTEM_ALERT_WINDOW allow";
        return ejecutarComandoShell(cmd1) && ejecutarComandoShell(cmd2) && ejecutarComandoShell(cmd3);
    }

    public static boolean ejecutarComandoShell(String command) {
        if (!isShizukuAvailable()) {
            Log.w("MediaProjectionHelper", "Shizuku no disponible para ejecutar: " + command);
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
                Log.i("MediaProjectionHelper", "Comando ejecutado: " + command);
                return true;
            } else {
                Log.e("MediaProjectionHelper", "Comando falló: " + command + " (código " + exitCode + ")");
                return false;
            }
        } catch (Exception e) {
            Log.e("MediaProjectionHelper", "Error ejecutando comando: " + command, e);
            return false;
        }
    }
}
