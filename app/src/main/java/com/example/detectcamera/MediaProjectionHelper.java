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

    public static boolean otorgarConsentimientoShizuku(String packageName) {
        String cmd1 = "appops set " + packageName + " PROJECT_MEDIA allow";
        String cmd2 = "pm grant " + packageName + " android.permission.PROJECT_MEDIA";
        return ejecutarComandoShell(cmd1) && ejecutarComandoShell(cmd2);
    }

    public static boolean ejecutarComandoShell(String command) {
        if (!isShizukuAvailable()) return false;

        try {
            Process process = Shizuku.newProcess(new String[]{"sh"}, null, null);
            OutputStream os = process.getOutputStream();
            os.write((command + "\n").getBytes());
            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            return process.waitFor() == 0;
        } catch (Exception e) {
            Log.e("MediaProjectionHelper", "Error ejecutando comando shell: " + e.getMessage());
            return false;
        }
    }
}
