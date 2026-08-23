package com.example.detectcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class ScreenCaptureController {

    private static final String TAG = "ScreenCapture";
    private static final String DISPLAY_NAME = "DetectCameraScreen";

    private final Context context;
    private final WebServer webServer;
    private final MediaProjection mediaProjection;

    private HandlerThread thread;
    private Handler handler;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private int width;
    private int height;
    private int densityDpi;

    private volatile boolean released;
    private volatile boolean projectionStopped;

    public ScreenCaptureController(Context context, MediaProjection mediaProjection, WebServer webServer) {
        this.context = context.getApplicationContext();
        this.mediaProjection = mediaProjection;
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (released || projectionStopped || mediaProjection == null || virtualDisplay != null) {
            return;
        }

        readDisplayMetrics();

        thread = new HandlerThread("ScreenCaptureThread");
        thread.start();
        handler = new Handler(thread.getLooper());

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                projectionStopped = true;
                Log.w(TAG, "MediaProjection fue detenida por el sistema.");
            }
        }, handler);

        imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                4
        );

        imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);

        try {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    DISPLAY_NAME,
                    width,
                    height,
                    densityDpi,
                    flags,
                    imageReader.getSurface(),
                    new VirtualDisplay.Callback() {
                        @Override
                        public void onPaused() {
                            Log.d(TAG, "VirtualDisplay pausado por Android.");
                        }

                        @Override
                        public void onResumed() {
                            Log.d(TAG, "VirtualDisplay reanudado.");
                            reaplicarSurface();
                        }

                        @Override
                        public void onStopped() {
                            Log.w(TAG, "VirtualDisplay detenido.");
                        }
                    },
                    handler
            );

            Log.i(TAG, "Captura de pantalla iniciada: " + width + "x" + height + " @" + densityDpi + "dpi");
        } catch (Throwable t) {
            Log.e(TAG, "No se pudo crear VirtualDisplay", t);
            closeReaderAndThread();
        }
    }

    private void readDisplayMetrics() {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = Math.max(1, metrics.widthPixels);
        height = Math.max(1, metrics.heightPixels);
        densityDpi = Math.max(1, metrics.densityDpi);

        final int maxWidth = 1280;
        if (width > maxWidth) {
            int newWidth = maxWidth;
            int newHeight = Math.max(1, Math.round(height * (newWidth / (float) width)));
            width = newWidth;
            height = newHeight;
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        Bitmap fullBitmap = null;
        Bitmap cleanBitmap = null;

        try {
            image = reader.acquireLatestImage();
            if (image == null || released) {
                return;
            }

            Image.Plane[] planes = image.getPlanes();
            if (planes.length == 0) {
                return;
            }

            Image.Plane plane = planes[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            if (pixelStride <= 0 || rowStride <= 0) {
                return;
            }

            int rowPadding = Math.max(0, rowStride - pixelStride * width);
            int bitmapWidth = width + rowPadding / pixelStride;

            buffer.rewind();
            fullBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
            fullBitmap.copyPixelsFromBuffer(buffer);

            cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, width, height);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(16 * 1024, width * height / 8));
            cleanBitmap.compress(Bitmap.CompressFormat.JPEG, 55, baos);

            byte[] jpeg = baos.toByteArray();
            if (webServer != null && jpeg.length > 0 && !released) {
                webServer.actualizarFramePantalla(jpeg);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error procesando frame de pantalla", t);
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {}
            }
            if (cleanBitmap != null && !cleanBitmap.isRecycled()) {
                cleanBitmap.recycle();
            }
            if (fullBitmap != null && !fullBitmap.isRecycled()) {
                fullBitmap.recycle();
            }
        }
    }

    private void reaplicarSurface() {
        if (released || projectionStopped || virtualDisplay == null || imageReader == null) {
            return;
        }
        try {
            Surface surface = imageReader.getSurface();
            if (surface != null && surface.isValid()) {
                virtualDisplay.setSurface(surface);
                virtualDisplay.resize(width, height, densityDpi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo reaplicar la Surface del VirtualDisplay", t);
        }
    }

    public synchronized boolean isRunning() {
        return !released && !projectionStopped && virtualDisplay != null;
    }

    public synchronized void release() {
        if (released) {
            return;
        }
        released = true;

        try {
            if (virtualDisplay != null) {
                virtualDisplay.setSurface(null);
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error liberando VirtualDisplay", t);
        }

        closeReaderAndThread();

        if (webServer != null) {
            webServer.actualizarFramePantalla(null);
        }
    }

    private void closeReaderAndThread() {
        try {
            if (imageReader != null) {
                imageReader.setOnImageAvailableListener(null, null);
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error cerrando ImageReader", t);
        }

        try {
            if (thread != null) {
                thread.quitSafely();
                thread = null;
                handler = null;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error cerrando hilo de pantalla", t);
        }
    }
}
