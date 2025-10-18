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
        textureView = new TextureView(context);
        addView(textureView);
        textureView.setSurfaceTextureListener(surfaceListener);
    }

    private final TextureView.SurfaceTextureListener surfaceListener = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            startBgThread();
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { closeCamera(); stopBgThread(); return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
    };

    @SuppressLint("MissingPermission")
    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics cc = manager.getCameraCharacteristics(id);
                if (cc.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            manager.openCamera(cameraId, stateCallback, bgHandler);
        } catch (Exception e) {
            emitError(e.getMessage());
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
            emitCameraReady();
        }
        @Override public void onDisconnected(CameraDevice camera) { camera.close(); cameraDevice = null; }
        @Override public void onError(CameraDevice camera, int error) { emitError("camera error: " + error); camera.close(); cameraDevice = null; }
    };

    private void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;

            texture.setDefaultBufferSize(1920, 1080);
            Surface surface = new Surface(texture);

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> saveImage(reader), bgHandler);

            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(
                Arrays.asList(surface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            captureSession.setRepeatingRequest(previewBuilder.build(), null, bgHandler);
                        } catch (Exception e) { emitError(e.getMessage()); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) { emitError("config failed"); }
                }, bgHandler
            );
        } catch (Exception e) { emitError(e.getMessage()); }
    }

    public void takePicture() {
        try {
            if (cameraDevice == null || captureSession == null) { emitError("Camera not ready"); return; }
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureSession.capture(captureBuilder.build(), null, bgHandler);
        } catch (Exception e) { emitError(e.getMessage()); }
    }

    private void saveImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            File dir = new File(getContext().getCacheDir(), "camera");
            if (!dir.exists()) dir.mkdirs();
            String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, name + ".jpg");

            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();

            emitPictureSaved(file.getAbsolutePath());
        } catch (Exception e) { emitError(e.getMessage()); }
        finally { if (image != null) image.close(); }
    }

    private void startBgThread() { bgThread = new HandlerThread("BG"); bgThread.start(); bgHandler = new Handler(bgThread.getLooper()); }
    private void stopBgThread() { if (bgThread != null) { bgThread.quitSafely(); try { bgThread.join(); } catch (Exception ignored) {} bgThread = null; bgHandler = null; } }
    private void closeCamera() { if (captureSession != null) captureSession.close(); if (cameraDevice != null) cameraDevice.close(); if (imageReader != null) imageReader.close(); }

    private void emitCameraReady() {
        ReactContext reactContext = (ReactContext) getContext();
        reactContext
            .getJSModule(RCTEventEmitter.class)
            .receiveEvent(getId(), "onCameraReady", null);
    }

    private void emitPictureSaved(String path) {
        ReactContext reactContext = (ReactContext) getContext();
        WritableMap map = Arguments.createMap();
        map.putString("path", path);
        reactContext
            .getJSModule(RCTEventEmitter.class)
            .receiveEvent(getId(), "onPictureSaved", map);
    }

    private void emitError(String msg) {
        ReactContext reactContext = (ReactContext) getContext();
        WritableMap map = Arguments.createMap();
        map.putString("message", msg);
        reactContext
            .getJSModule(RCTEventEmitter.class)
            .receiveEvent(getId(), "onError", map);
    }
}
