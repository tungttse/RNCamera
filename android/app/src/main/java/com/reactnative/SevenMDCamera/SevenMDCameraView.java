package com.reactnative.SevenMDCamera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
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
 * Camera view using old Camera1 API for better compatibility on Android 7–10 devices.
 */
public class SevenMDCameraView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "SevenMDCameraView";

    private TextureView textureView;
    private Camera camera;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final ReactContext reactContext;

    public SevenMDCameraView(Context context) {
        super(context);
        this.reactContext = (ReactContext) context;
        init();
    }

    private void init() {
        textureView = new TextureView(getContext());
        addView(textureView);
        textureView.setSurfaceTextureListener(this);
    }

    // region --- Lifecycle and Camera Control ---

    private void startBgThread() {
        bgThread = new HandlerThread("CameraBackground");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
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
        }
    }

    private void openCamera() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(getContext(), android.Manifest.permission.CAMERA)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission not granted");
            emitError("Camera permission not granted");
            return;
        }
        bgHandler.post(() -> {
            try {
                // Nếu camera cũ chưa giải phóng, đợi thêm
                if (camera != null) {
                    Log.w(TAG, "Camera instance not null, releasing before reopen...");
                    camera.release();
                    camera = null;
                    Thread.sleep(200);
                }
    
                // Đợi surfaceTexture thật sự sẵn sàng
                SurfaceTexture surface = textureView.getSurfaceTexture();
                if (surface == null) {
                    Log.w(TAG, "SurfaceTexture not ready yet, retrying in 300ms...");
                    bgHandler.postDelayed(this::openCamera, 300);
                    return;
                }
    
                // Mở camera
                Log.d(TAG, "Opening Camera1...");

                try {
                    camera = Camera.openLegacy(0, android.hardware.Camera.CAMERA_HAL_API_VERSION_1_0);
                } catch (Exception e) {
                    emitError("openLegacy failed: " + e.getMessage());
                }

                // camera = Camera.open(0);
    
                if (camera == null) {
                    emitError("Camera.open() returned null");
                    return;
                }
    
                camera.setPreviewTexture(surface);
                camera.startPreview();
                emitCameraReady();
                Log.d(TAG, "Camera1 opened successfully");
    
            } catch (RuntimeException e) {
                Log.e(TAG, "Runtime error opening camera: " + e.getMessage());
                emitError("RuntimeException: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Failed to open Camera1: " + e.getMessage());
                emitError("openCamera failed: " + e.getMessage());
            }
        });
    }
    

    private void closeCamera() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Exception ignored) {}
            try {
                camera.release();
                Log.d(TAG, "Camera released");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera: " + e.getMessage());
            } finally {
                camera = null;
            }
        }
    }
    

    // endregion

    // region --- Capture ---

    public void capture() {
        if (camera == null) {
            emitError("capture called but camera == null");
            return;
        }

        try {
            camera.takePicture(null, null, (data, cam) -> {
                File file = new File(getContext().getCacheDir(), "photo_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                    fos.flush();

                    WritableMap map = Arguments.createMap();
                    map.putString("uri", "file://" + file.getAbsolutePath());
                    emitPictureSaved(map);
                    Log.d(TAG, "Picture saved: " + file.getAbsolutePath());
                } catch (IOException e) {
                    emitError("Error saving picture: " + e.getMessage());
                }

                // restart preview
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

    // region --- TextureView Callbacks ---

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "Surface available, starting camera...");
        startBgThread();

        // Trì hoãn một chút trước khi mở camera để tránh lỗi SurfaceTexture not ready yet  
        bgHandler.postDelayed(this::openCamera, 300);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "Surface destroyed, closing camera...");
        closeCamera();
        stopBgThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    // endregion

    // region --- React Event Emitters ---

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
        reactContext
                .getJSModule(RCTEventEmitter.class)
                .receiveEvent(getId(), eventName, event);
    }

    // endregion
}
