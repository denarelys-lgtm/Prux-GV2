package com.example.detectcamera;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Enumeration;

public class CameraService extends Service {

    private static final String CHANNEL_ID = "CameraServiceChannel";
    private static final String PROJECTION_CHANNEL_ID = "ProjectionPromptChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int PROJECTION_NOTIF_ID = 99;
    private static final int PUERTO_WEB = 8080;

    private WebServer webServer;
    private ScreenCaptureController screenCaptureController;
    private MediaProjection activeMediaProjection;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReaderCamera;
    private boolean camaraActiva = false;
    private String selectedCameraId = "0";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // Aplicar exenciones vía Shizuku para evitar SecurityException en segundo plano
        ShizukuBypass.aplicarExencionesBackground(this);

        try {
            Intent serverIntent = new Intent(this, ServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serverIntent);
            } else {
                startService(serverIntent);
            }
        } catch (Throwable t) {
            Log.w("CameraService", "No se pudo asegurar ServerService", t);
        }

        backgroundThread = new HandlerThread("CameraServiceBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DetectCamera::ServiceWakeLock");
            wakeLock.acquire();
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DetectCamera::WifiLock");
            wifiLock.acquire();
        }

        iniciarServidor("", "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean esProyeccion = intent != null && "ACTION_START_PROJECTION".equals(intent.getAction());

        actualizarNotificacionYServicio(esProyeccion);

        if (esProyeccion) {
            cancelarNotificacionSolicitudProyeccion();
            int resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("EXTRA_DATA");
            iniciarProyeccionPantalla(resultCode, data);
        } else if (intent != null) {
            String user = intent.getStringExtra("USER_PARAM");
            String pass = intent.getStringExtra("PASS_PARAM");
            if (webServer != null && (user != null || pass != null)) {
                webServer.setCredenciales(user, pass);
            }
        }

        return START_STICKY;
    }

    private void actualizarNotificacionYServicio(boolean incluirMediaProjection) {
        String ip = obtenerIpReal();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Servidor Transmitiendo")
                .setContentText("IP: " + ip + ":" + PUERTO_WEB)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = 0;

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }

            if (incluirMediaProjection && Build.VERSION.SDK_INT >= 34) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }

            if (Build.VERSION.SDK_INT >= 34) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }

            try {
                if (types != 0) {
                    startForeground(NOTIFICATION_ID, notification, types);
                } else {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } catch (SecurityException e) {
                Log.w("CameraService", "SecurityException al iniciar FGS. Reintentando con Shizuku y fallback.", e);
                
                ShizukuBypass.aplicarExencionesBackground(this);

                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    } else {
                        startForeground(NOTIFICATION_ID, notification);
                    }
                } catch (Exception ex) {
                    Log.e("CameraService", "Fallback final para evitar crash del servicio", ex);
                    startForeground(NOTIFICATION_ID, notification);
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private synchronized void iniciarServidor(String user, String pass) {
        try {
            webServer = WebServerManager.getOrStart(this);
            WebServerManager.attachCameraService(this);
            if (user != null || pass != null) {
                webServer.setCredenciales(user, pass);
            }

            String ip = obtenerIpReal();
            mostrarToastEnUI("Servidor Activo: http://" + ip + ":" + PUERTO_WEB);

            Intent intentIp = new Intent("com.example.detectcamera.UPDATE_IP");
            intentIp.setPackage(getPackageName());
            intentIp.putExtra("IP_ADDRESS", ip + ":" + PUERTO_WEB);
            sendBroadcast(intentIp);
        } catch (IOException e) {
            Log.e("CameraService", "Error WebServer: " + e.getMessage(), e);
        }
    }

    public void activarCapturaPantalla() {
        backgroundHandler.post(() -> {
            if (screenCaptureController != null && screenCaptureController.isRunning()) return;

            if (MediaProjectionHelper.isShizukuAvailable()) {
                MediaProjectionHelper.otorgarConsentimientoShizuku(getPackageName());
                MediaProjectionHelper.ejecutarComandoShell("am start -n " + getPackageName() + "/.ProjectionActivity");
            } else {
                mostrarNotificacionSolicitudProyeccion();
            }
        });
    }

    private void mostrarNotificacionSolicitudProyeccion() {
        Intent pIntent = new Intent(this, ProjectionActivity.class);
        pIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, pIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, PROJECTION_CHANNEL_ID)
                .setContentTitle("Solicitud de Transmisión de Pantalla")
                .setContentText("Toca aquí para autorizar la captura de pantalla.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(PROJECTION_NOTIF_ID, notification);
        }
    }

    private void cancelarNotificacionSolicitudProyeccion() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(PROJECTION_NOTIF_ID);
        }
    }

    private synchronized void iniciarProyeccionPantalla(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (projectionManager != null) {
            MediaProjection mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            if (mediaProjection != null) {
                this.activeMediaProjection = mediaProjection;
                if (screenCaptureController != null) {
                    screenCaptureController.release();
                }
                screenCaptureController = new ScreenCaptureController(this, mediaProjection, webServer);
                screenCaptureController.start();
            }
        }
    }

    public synchronized MediaProjection getMediaProjection() {
        return this.activeMediaProjection;
    }

    public synchronized void detenerProyeccionPantalla() {
        if (screenCaptureController != null) {
            screenCaptureController.release();
            screenCaptureController = null;
        }
        this.activeMediaProjection = null;
        actualizarNotificacionYServicio(false);
    }

    public synchronized void iniciarCamara() {
        if (camaraActiva) return;
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            imageReaderCamera = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
            imageReaderCamera.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img != null) {
                        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        if (webServer != null) {
                            webServer.actualizarFrameCamara(bytes);
                        }
                    }
                } catch (Exception e) {
                    Log.e("CameraService", "Error frame cámara", e);
                } finally {
                    if (img != null) img.close();
                }
            }, backgroundHandler);

            manager.openCamera(selectedCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    crearSesionCapturaCamara();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                }
            }, backgroundHandler);

            camaraActiva = true;
            actualizarNotificacionYServicio(screenCaptureController != null && screenCaptureController.isRunning());
        } catch (Exception e) {
            Log.e("CameraService", "Error abriendo cámara: " + e.getMessage(), e);
        }
    }

    private void crearSesionCapturaCamara() {
        try {
            Surface surface = imageReaderCamera.getSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (Exception e) {
                        Log.e("CameraService", "Error repitiendo request de cámara", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e("CameraService", "Error creando sesión cámara", e);
        }
    }

    public synchronized void detenerCamara() {
        if (!camaraActiva) return;
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReaderCamera != null) {
                imageReaderCamera.close();
                imageReaderCamera = null;
            }
        } catch (Exception e) {
            Log.e("CameraService", "Error deteniendo cámara", e);
        }
        camaraActiva = false;
        if (webServer != null) {
            webServer.actualizarFrameCamara(null);
        }
        actualizarNotificacionYServicio(screenCaptureController != null && screenCaptureController.isRunning());
    }

    public synchronized void alternarCamara() {
        boolean estabaActiva = camaraActiva;
        if (camaraActiva) detenerCamara();
        selectedCameraId = "0".equals(selectedCameraId) ? "1" : "0";
        if (estabaActiva) iniciarCamara();
    }

    public String obtenerIpReal() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getAddress().length == 4) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("CameraService", "Error obteniendo IP: " + ex.getMessage());
        }
        return "127.0.0.1";
    }

    private void mostrarToastEnUI(String mensaje) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), mensaje, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onDestroy() {
        detenerCamara();
        detenerProyeccionPantalla();

        WebServerManager.detachCameraService(this);
        webServer = null;

        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (backgroundThread != null) backgroundThread.quitSafely();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel serviceChannel = new NotificationChannel(
                        CHANNEL_ID, "Camera Service Channel", NotificationManager.IMPORTANCE_LOW);

                NotificationChannel projChannel = new NotificationChannel(
                        PROJECTION_CHANNEL_ID, "Solicitudes de Pantalla", NotificationManager.IMPORTANCE_HIGH);
                projChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

                manager.createNotificationChannel(serviceChannel);
                manager.createNotificationChannel(projChannel);
            }
        }
    }
}
