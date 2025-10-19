package com.reactnative.SevenMDCamera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * SevenMDCameraView — Safe Camera1 implementation optimized for MediaTek devices.
 * Handles open/close race conditions, background threads, and HAL retry logic.
 */
public class SevenMDCameraView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "SevenMDCameraView";

    private TextureView textureView;
    private Camera camera;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final ReactContext reactContext;

    private boolean isOpening = false;
    private boolean isSurfaceReady = false;
    private int retryCount = 0;

    public SevenMDCameraView(Context context) {
        super(context);
        this.reactContext = (ReactContext) context;
        init();
    }

    private void init() {
        textureView = new TextureView(getContext());
        addView(textureView);
        textureView.setSurfaceTextureListener(this);
        Log.d(TAG, "Camera view initialized");
    }

    // region ===== Background Thread =====
    private void startBgThread() {
        if (bgThread != null) return;
        bgThread = new HandlerThread("CameraBackground");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        Log.d(TAG, "Background thread started");
    }

    private void stopBgThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try {
                bgThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
            bgThread = null;
            bgHandler = null;
            Log.d(TAG, "Background thread stopped");
        }
    }
    // endregion

    // region ===== Camera Control =====
    private synchronized void openCameraSafe() {
        if (camera != null || isOpening) {
            Log.w(TAG, "Camera already opening/opened, skipping.");
            return;
        }
        isOpening = true;

        try {
            Log.d(TAG, "Attempting to open Camera1...");
            camera = Camera.open(0); // back camera

            if (camera == null) {
                emitError("Camera.open() returned null");
                return;
            }

            camera.setPreviewTexture(textureView.getSurfaceTexture());
            camera.startPreview();
            emitCameraReady();
            retryCount = 0;
            Log.d(TAG, "✅ Camera opened successfully");

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "unknown";
            Log.e(TAG, "❌ Failed to open Camera1: " + msg);

            if (msg.contains("Fail to connect to camera service") && retryCount < 3) {
                retryCount++;
                Log.w(TAG, "Camera service busy, retrying in 1s... (" + retryCount + ")");
                new Handler(Looper.getMainLooper()).postDelayed(this::openCameraSafe, 1000);
            } else {
                emitError("openCamera failed: " + msg);
            }

        } finally {
            isOpening = false;
        }
    }

    private synchronized void closeCameraSafe() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {}
            try {
                camera.setPreviewCallback(null);
            } catch (Exception ignored) {}
            try {
                camera.release();
                Log.d(TAG, "Camera released cleanly");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera: " + e.getMessage());
            }
            camera = null;
        }
    }
    // endregion

    // region ===== Capture =====
    public void capture() {
        if (camera == null) {
            emitError("capture() called but camera == null");
            return;
        }

        try {
            camera.takePicture(null, null, (data, cam) -> {
                File file = new File(getContext().getCacheDir(),
                        "photo_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                    fos.flush();

                    WritableMap map = Arguments.createMap();
                    map.putString("uri", "file://" + file.getAbsolutePath());
                    emitPictureSaved(map);
                    Log.d(TAG, "📸 Picture saved: " + file.getAbsolutePath());
                } catch (IOException e) {
                    emitError("Error saving picture: " + e.getMessage());
                }

                try {
                    cam.startPreview();
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to restart preview: " + ex.getMessage());
                }
            });
        } catch (Exception e) {
            emitError("capture() failed: " + e.getMessage());
        }
    }
    // endregion

    // region ===== TextureView Callbacks =====
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "Surface available, scheduling camera open...");
        isSurfaceReady = true;
        startBgThread();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isSurfaceReady) {
                Log.w(TAG, "Surface lost before open, skipping openCamera.");
                return;
            }
            openCameraSafe();
        }, 800); // delay 800ms for MediaTek HAL stabilization
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "Surface destroyed, closing camera...");
        isSurfaceReady = false;

        // delay 300ms to ensure HAL finishes pending ops
        new Handler(Looper.getMainLooper()).postDelayed(this::closeCameraSafe, 300);
        stopBgThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
    // endregion

    // region ===== React Events =====
    private void emitCameraReady() {
        WritableMap event = Arguments.createMap();
        event.putString("status", "ready");
        sendEvent("onCameraReady", event);
    }

    private void emitError(String message) {
        WritableMap event = Arguments.createMap();
        event.putString("error", message);
        sendEvent("onError", event);
    }

    private void emitPictureSaved(WritableMap data) {
        sendEvent("onPictureSaved", data);
    }

    private void sendEvent(String eventName, @Nullable WritableMap event) {
        reactContext.getJSModule(RCTEventEmitter.class)
                .receiveEvent(getId(), eventName, event);
    }
    // endregion
}
