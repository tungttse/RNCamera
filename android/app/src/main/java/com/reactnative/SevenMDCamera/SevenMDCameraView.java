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
        bgHandler.post(() -> {
            try {
                camera = Camera.open(0); // back camera
                if (camera == null) {
                    emitError("Camera.open() returned null");
                    return;
                }
                camera.setPreviewTexture(textureView.getSurfaceTexture());
                camera.startPreview();
                emitCameraReady();
                Log.d(TAG, "Camera1 opened successfully");
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
                camera.release();
                camera = null;
                Log.d(TAG, "Camera released");
            } catch (Exception e) {
                Log.e(TAG, "Error closing camera: " + e.getMessage());
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
        openCamera();
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
