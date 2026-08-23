package com.example.detectcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;

import rikka.shizuku.Shizuku;

/**
 * Servicio persistente del servidor. No captura cámara/micrófono/pantalla por sí mismo;
 * esas capacidades siguen sujetas a los permisos y controles de Android.
 */
public class ServerService extends Service {
    private static final String TAG = "ServerService";
    private static final String CHANNEL_ID = "ServerServiceChannel";
    private static final int NOTIFICATION_ID = 7001;
    private static final int SHIZUKU_CHECK_MS = 2000;

    private volatile boolean running;
    private Thread shizukuMonitor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Inicializando servidor..."));

        try {
            WebServerManager.start(this);
            running = true;
            updateNotification();
        } catch (IOException e) {
            Log.e(TAG, "No se pudo iniciar WebServer", e);
        }

        iniciarMonitorShizuku();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void iniciarMonitorShizuku() {
        if (shizukuMonitor != null) return;
        shizukuMonitor = new Thread(() -> {
            boolean previous = false;
            while (running && !Thread.currentThread().isInterrupted()) {
                boolean available = false;
                try {
                    available = Shizuku.pingBinder()
                            && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                } catch (Throwable ignored) {
                }

                if (available != previous) {
                    previous = available;
                    Intent event = new Intent("com.example.detectcamera.SHIZUKU_STATE");
                    event.setPackage(getPackageName());
                    event.putExtra("available", available);
                    sendBroadcast(event);
                    Log.i(TAG, "Estado Shizuku: " + available);
                }

                try {
                    Thread.sleep(SHIZUKU_CHECK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ShizukuMonitor");
        shizukuMonitor.start();
    }

    private Notification buildNotification(String text) {
        String ip = "Servidor local";
        WebServer server = WebServerManager.get();
        if (server != null) {
            ip = "Servidor activo en :8080";
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DetectCamera")
                .setContentText(text + " · " + ip)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification("Servidor activo"));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID,
                        "Servidor DetectCamera",
                        NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (shizukuMonitor != null) {
            shizukuMonitor.interrupt();
            shizukuMonitor = null;
        }
        WebServerManager.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
