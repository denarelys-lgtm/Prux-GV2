package com.example.detectcamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ShellScreenCapture {

    private static final String TAG = "ShellScreenCapture";

    /**
     * Toma una foto de la pantalla usando los comandos de root/shell de Shizuku.
     * Esto ignora por completo el FLAG_SECURE de las aplicaciones.
     */
    public static byte[] capturarFramePng() {
        Process process = null;
        try {
            // Ejecutamos el comando nativo de Android con permisos de Shell (UID 2000)
            process = rikka.shizuku.Shizuku.newProcess(
                    new String[]{"/system/bin/screencap", "-p"},
                    null,
                    null
            );

            InputStream inputStream = process.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

            if (bitmap != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // Comprimimos a JPEG con calidad 60% para que vuele rápido y sin trabarse
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos);
                byte[] jpegBytes = baos.toByteArray();
                
                bitmap.recycle(); // Limpiamos la memoria del teléfono
                return jpegBytes;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al capturar pantalla vía Shell/Shizuku: " + e.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}
