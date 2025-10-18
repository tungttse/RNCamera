package com.reactnative.SevenMDCamera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.UIManagerHelper;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class SevenMDCameraView extends FrameLayout {
    private static final String TAG = "SevenMDCamera";
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private ImageReader imageReader;
    private Size previewSize;
    private String cameraId;

    public SevenMDCameraView(Context context) {
        super(context);
        Log.d(TAG, "SevenMDCameraView constructor called");
        textureView = new TextureView(context);
        addView(textureView);
        textureView.setSurfaceTextureListener(surfaceListener);
        Log.d(TAG, "TextureView created and surface listener set");
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            Log.d(TAG, "onSurfaceTextureAvailable called - width: " + width + ", height: " + height);
            startBgThread();

            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.postDelayed(() -> {
                Log.d(TAG, "Opening camera after delay");
                openCamera();
            }, 1000);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
            Log.d(TAG, "onSurfaceTextureDestroyed called - closing camera and stopping background thread");
            closeCamera();
            stopBgThread();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture s) {
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        Log.d(TAG, "openCamera called");
        try {
            CameraManager manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            Log.d(TAG, "CameraManager obtained");
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Log.d(TAG, "Found back camera with ID: " + cameraId);
                    break;
                }
            }
            Log.d(TAG, "Opening camera with ID: " + cameraId);

            if (cameraDevice != null) {
                Log.d(TAG, "Closing previous camera before opening new one");
                cameraDevice.close();
                cameraDevice = null;
            }

            manager.openCamera(cameraId, stateCallback, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera: " + e.getMessage(), e);
            emitError(e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            Log.d(TAG, "Camera opened successfully");
            cameraDevice = camera;
            startPreview();
            emitCameraReady();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.d(TAG, "Camera disconnected - closing camera");
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera error occurred: " + error);
            emitError("camera error: " + error);
            camera.close();
            cameraDevice = null;
        }
    };

    private void startPreview() {
        Log.d(TAG, "startPreview called");
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                Log.w(TAG, "SurfaceTexture is null, cannot start preview");
                return;
            }

            Log.d(TAG, "Setting default buffer size to 1920x1080");
            texture.setDefaultBufferSize(1920, 1080);
            Surface surface = new Surface(texture);

            Log.d(TAG, "Creating ImageReader for JPEG capture");
            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> saveImage(reader), bgHandler);

            Log.d(TAG, "Creating capture request builder for preview");
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);

            Log.d(TAG, "Creating capture session");
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            Log.d(TAG, "Capture session configured successfully");
                            captureSession = session;
                            try {
                                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                captureSession.setRepeatingRequest(previewBuilder.build(), null, bgHandler);
                                Log.d(TAG, "Preview started with repeating request");
                            } catch (Exception e) {
                                Log.e(TAG, "Error setting repeating request: " + e.getMessage(), e);
                                emitError(e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configuration failed");
                            emitError("config failed");
                        }
                    }, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Error in startPreview: " + e.getMessage(), e);
            emitError(e.getMessage());
        }
    }

    public void takePicture() {
        Log.d(TAG, "takePicture called");
        try {
            if (cameraDevice == null || captureSession == null) {
                Log.w(TAG, "Camera not ready - cameraDevice: " + (cameraDevice != null) + ", captureSession: "
                        + (captureSession != null));
                emitError("Camera not ready");
                return;
            }
            Log.d(TAG, "Creating capture request for still capture");
            CaptureRequest.Builder captureBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            Log.d(TAG, "Capturing picture");
            captureSession.capture(captureBuilder.build(), null, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "Error taking picture: " + e.getMessage(), e);
            emitError(e.getMessage());
        }
    }

    private void saveImage(ImageReader reader) {
        Log.d(TAG, "saveImage called");
        Image image = null;
        try {
            image = reader.acquireNextImage();
            Log.d(TAG, "Image acquired from reader");
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Log.d(TAG, "Image data extracted, size: " + bytes.length + " bytes");

            File dir = new File(getContext().getCacheDir(), "camera");
            if (!dir.exists()) {
                Log.d(TAG, "Creating camera directory: " + dir.getAbsolutePath());
                dir.mkdirs();
            }
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, name + ".jpg");
            Log.d(TAG, "Saving image to: " + file.getAbsolutePath());

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
            Log.d(TAG, "Image saved successfully");

            emitPictureSaved(file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving image: " + e.getMessage(), e);
            emitError(e.getMessage());
        } finally {
            if (image != null) {
                Log.d(TAG, "Closing image");
                image.close();
            }
        }
    }

    private void startBgThread() {
        Log.d(TAG, "Starting background thread");
        bgThread = new HandlerThread("BG");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        Log.d(TAG, "Background thread started successfully");
    }

    private void stopBgThread() {
        Log.d(TAG, "Stopping background thread");
        if (bgThread != null) {
            bgThread.quitSafely();
            try {
                bgThread.join();
                Log.d(TAG, "Background thread stopped successfully");
            } catch (Exception ignored) {
                Log.w(TAG, "Exception while joining background thread", ignored);
            }
            bgThread = null;
            bgHandler = null;
        }
    }

    private void closeCamera() {
        Log.d(TAG, "closeCamera called");
        if (captureSession != null) {
            Log.d(TAG, "Closing capture session");
            captureSession.close();
        }
        if (cameraDevice != null) {
            Log.d(TAG, "Closing camera device");
            cameraDevice.close();
        }
        if (imageReader != null) {
            Log.d(TAG, "Closing image reader");
            imageReader.close();
        }
        Log.d(TAG, "Camera closed successfully");
    }

    private void emitCameraReady() {
        Log.d(TAG, "Emitting camera ready event");
        ReactContext reactContext = (ReactContext) getContext();
        reactContext
                .getJSModule(RCTEventEmitter.class)
                .receiveEvent(getId(), "onCameraReady", null);
    }

    private void emitPictureSaved(String path) {
        Log.d(TAG, "Emitting picture saved event with path: " + path);
        ReactContext reactContext = (ReactContext) getContext();
        WritableMap map = Arguments.createMap();
        map.putString("path", path);
        reactContext
                .getJSModule(RCTEventEmitter.class)
                .receiveEvent(getId(), "onPictureSaved", map);
    }

    private void emitError(String msg) {
        Log.e(TAG, "Emitting error event: " + msg);
        ReactContext reactContext = (ReactContext) getContext();
        WritableMap map = Arguments.createMap();
        map.putString("message", msg);
        reactContext
                .getJSModule(RCTEventEmitter.class)
                .receiveEvent(getId(), "onError", map);
    }
}
