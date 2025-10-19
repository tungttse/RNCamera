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
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactMethod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.Manifest;

/**
 * SevenMDCameraView — Camera1 lifecycle theo phong cách Google Camera1.java
 * - Tách openCamera / setUpPreview / startCameraPreview
 * - Quản lý SurfaceTexture đúng vòng đời
 * - Mọi thao tác camera trên background thread để tránh race/ANR
 * - Log chi tiết từng bước
 */
@SuppressWarnings("deprecation")
public class SevenMDCameraView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "ttt";

    // region ===== UI / Context =====
    private final ReactContext reactContext;
    private TextureView textureView;
    // endregion

    // region ===== Camera / Threading =====
    private Camera camera;                      // Trạng thái camera
    private HandlerThread bgThread;             // Luồng nền cho thao tác camera
    private Handler bgHandler;                  // Handler của luồng nền
    // endregion

    // region ===== State flags =====
    private boolean isOpening = false;          // Đang mở camera?
    private boolean isPreviewActive = false;    // Preview đang chạy?
    private boolean isSurfaceReady = false;     // SurfaceTexture đã sẵn sàng?
    private boolean surfaceWasDestroyed = false;// Surface vừa bị destroy?
    private boolean mustReattach = false;       // Cần gắn lại preview khi surface quay lại?
    private int retryCount = 0;                 // Số lần retry open camera khi HAL bận
    private long lastCloseTime = 0;
    // endregion

    public SevenMDCameraView(Context context) {
        super(context);
        this.reactContext = (ReactContext) context;
        init();
    }

    // region ===== Init UI =====
    private void init() {
        Log.d(TAG, "[init] Bắt đầu khởi tạo view...");
        textureView = new TextureView(getContext());
        addView(textureView);
        textureView.setSurfaceTextureListener(this);
        Log.d(TAG, "[init] Khởi tạo xong. TextureView đã set SurfaceTextureListener");
    }
    // endregion

 

    private boolean ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // Camera permission is already granted, proceed with camera operations
            // For example, launch the camera or start a camera preview
            return true;
        } else {
            // Camera permission is not granted, request it from the user
            ActivityCompat.requestPermissions((Activity) getContext(), new String[]{Manifest.permission.CAMERA}, 1001);
            return false;
        }
    }

    // region ===== Background Thread =====
    private void startBgThread() {
        if (bgThread != null) {
            Log.d(TAG, "[startBgThread] Bỏ qua: bgThread đã tồn tại");
            return;
        }
        Log.d(TAG, "[startBgThread] Tạo HandlerThread 'CameraBackground'...");
        bgThread = new HandlerThread("CameraBackground");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        Log.d(TAG, "[startBgThread] HandlerThread đã sẵn sàng");
    }

    private void stopBgThread() {
        if (bgThread == null) {
            Log.d(TAG, "[stopBgThread] Bỏ qua: bgThread == null");
            return;
        }
        Log.d(TAG, "[stopBgThread] Dừng HandlerThread an toàn...");
        try {
            bgThread.quitSafely();
            bgThread.join();
            Log.d(TAG, "[stopBgThread] HandlerThread đã dừng");
        } catch (InterruptedException e) {
            Log.e(TAG, "[stopBgThread] Lỗi khi join thread: ", e);
        } finally {
            bgThread = null;
            bgHandler = null;
            Log.d(TAG, "[stopBgThread] Đã giải phóng tham chiếu thread/handler");
        }
    }
    // endregion

    // region ===== Camera Lifecycle (Google-style) =====

    /** B1: Mở camera trên bg thread (không block UI) */
    private void openCameraSafe() {
        if (!ensureCameraPermission()) {
            Log.w(TAG, "[openCameraSafe] Permission not ready, abort openCamera.");
            return;
        }
        long sinceClose = System.currentTimeMillis() - lastCloseTime;
        if (sinceClose < 1000) {
            Log.w(TAG, "[openCameraSafe] Camera vừa đóng " + sinceClose + "ms trước → hoãn reopen");
            new Handler(Looper.getMainLooper()).postDelayed(this::openCameraSafe, 1000 - sinceClose);
            return;
        }

        if (camera != null || isOpening) {
            Log.w(TAG, "[openCameraSafe] Bỏ qua: camera!=null(" + (camera != null) + "), isOpening=" + isOpening);
            return;
        }
        if (bgHandler == null) {
            Log.d(TAG, "[openCameraSafe] bgHandler == null → startBgThread()");
            startBgThread();
        }
        isOpening = true;
        Log.d(TAG, "[openCameraSafe] Gửi yêu cầu mở camera lên bg thread...");

        bgHandler.post(() -> {
            Log.d(TAG, "[openCameraSafe/bg] Bắt đầu mở camera (Camera.open(0))...");
            try {
                camera = Camera.open(0); // back camera
                if (camera == null) {
                    Log.e(TAG, "[openCameraSafe/bg] Camera.open() trả về null");
                    emitError("Camera.open() returned null");
                    return;
                }
                Log.d(TAG, "[openCameraSafe/bg] Camera.open() thành công, chuyển sang setUpPreview()");
                setUpPreview(); // B2
                Log.d(TAG, "[openCameraSafe/bg] setUpPreview() xong, chuyển sang startCameraPreview()");
                startCameraPreview(); // B3

                retryCount = 0;
                emitCameraReady();
                Log.d(TAG, "✅ [openCameraSafe/bg] Camera mở + preview đã start thành công");

            } catch (Exception e) {
                String msg = (e.getMessage() != null ? e.getMessage() : "unknown");
                Log.e(TAG, "❌ [openCameraSafe/bg] Lỗi mở camera: " + msg, e);

                if (msg.contains("Fail to connect to camera service") && retryCount < 3) {
                    retryCount++;
                    Log.w(TAG, "[openCameraSafe/bg] Camera service bận, sẽ retry sau 1s... (lần " + retryCount + ")");
                    int delay = 1200 + retryCount * 300; // tăng dần 1.2s → 1.5s → 1.8s
                    new Handler(Looper.getMainLooper()).postDelayed(this::openCameraSafe, delay);
                } else {
                    emitError("openCamera failed: " + msg);
                }

            } finally {
                isOpening = false;
                Log.d(TAG, "[openCameraSafe/bg] Kết thúc openCamera, isOpening=false");
            }
        });
    }

    /** B2: Gắn đích preview (Camera → SurfaceTexture) như setUpPreview() trong Camera1.java */
    private void setUpPreview() throws IOException {
        Log.d(TAG, "[setUpPreview] Bắt đầu...");
        if (camera == null) {
            Log.w(TAG, "[setUpPreview] Bỏ qua: camera == null");
            return;
        }
        SurfaceTexture surface = textureView.getSurfaceTexture();
        Log.d(TAG, "[setUpPreview] Lấy SurfaceTexture từ TextureView: " + (surface != null));
        if (surface == null) {
            Log.w(TAG, "[setUpPreview] SurfaceTexture null → không thể setPreviewTexture ngay");
            mustReattach = true;
            return;
        }
        camera.setPreviewTexture(surface);
        Log.d(TAG, "[setUpPreview] Đã setPreviewTexture(surface).");
    }

    /** B3: Start preview, giống startCameraPreview() của Camera1.java */
    private void startCameraPreview() {
        Log.d(TAG, "[startCameraPreview] Bắt đầu...");
        if (camera == null) {
            Log.w(TAG, "[startCameraPreview] Bỏ qua: camera == null");
            return;
        }
        if (isPreviewActive) {
            Log.d(TAG, "[startCameraPreview] Preview đã chạy → bỏ qua");
            return;
        }
        try {
            camera.startPreview();
            isPreviewActive = true;
            Log.d(TAG, "▶️ [startCameraPreview] camera.startPreview() OK, isPreviewActive=true");
        } catch (Exception e) {
            isPreviewActive = false;
            Log.e(TAG, "⛔ [startCameraPreview] Lỗi startPreview: " + e.getMessage(), e);
        }
    }

    /** Tái gắn preview và khởi động lại khi surface được recreate (học từ updateSurface+start của Camera1) */
    private void restartPreviewIfNeeded() {
        Log.d(TAG, "[restartPreviewIfNeeded] Bắt đầu...");
        if (camera == null) {
            Log.d(TAG, "[restartPreviewIfNeeded] camera == null → openCameraSafe()");
            openCameraSafe();
            return;
        }
        if (bgHandler == null) {
            Log.d(TAG, "[restartPreviewIfNeeded] bgHandler == null → startBgThread()");
            startBgThread();
        }
        bgHandler.post(() -> {
            Log.d(TAG, "[restartPreviewIfNeeded/bg] Dừng preview (nếu đang chạy)...");
            try { camera.stopPreview(); } catch (Exception ignore) {}
            isPreviewActive = false;

            Log.d(TAG, "[restartPreviewIfNeeded/bg] Gọi setUpPreview() để gắn lại surface...");
            try {
                setUpPreview();
            } catch (Exception e) {
                Log.e(TAG, "[restartPreviewIfNeeded/bg] Lỗi setUpPreview(): " + e.getMessage(), e);
            }

            Log.d(TAG, "[restartPreviewIfNeeded/bg] Gọi startCameraPreview()...");
            startCameraPreview();
            Log.d(TAG, "[restartPreviewIfNeeded/bg] Hoàn tất restart preview.");
        });
    }

    /** Giải phóng camera an toàn (tương tự stop()+release trong Camera1) */
    private void closeCameraSafe() {
        lastCloseTime = System.currentTimeMillis();
        Log.d(TAG, "[closeCameraSafe] Bắt đầu đóng camera...");
        if (camera == null) {
            Log.d(TAG, "[closeCameraSafe] Bỏ qua: camera == null");
            return;
        }
        if (bgHandler == null) {
            Log.d(TAG, "[closeCameraSafe] bgHandler == null → startBgThread() tạm để dọn dẹp");
            startBgThread();
        }
        bgHandler.post(() -> {
            try {
                Log.d(TAG, "[closeCameraSafe/bg] stopPreview() nếu cần...");
                try { camera.stopPreview(); } catch (Exception ignore) {}
                isPreviewActive = false;

                Log.d(TAG, "[closeCameraSafe/bg] clear preview callback...");
                try { camera.setPreviewCallback(null); } catch (Exception ignore) {}

                Log.d(TAG, "[closeCameraSafe/bg] release camera...");
                camera.release();
                Log.d(TAG, "🛑 [closeCameraSafe/bg] Camera released cleanly");
            } catch (Exception e) {
                Log.e(TAG, "⛔ [closeCameraSafe/bg] Lỗi khi release camera: " + e.getMessage(), e);
            } finally {
                camera = null;
                Log.d(TAG, "[closeCameraSafe/bg] Đặt camera=null, isPreviewActive=false");
            }
        });
    }
    // endregion

    // region ===== Capture =====
    @ReactMethod
    public void capture(Promise promise) {
        Log.d(TAG, "[capture] Bắt đầu chụp...");
        if (camera == null) {
            Log.e(TAG, "[capture] Bỏ qua: camera == null");
            emitError("capture() called but camera == null");
            return;
        }
        try {
            camera.takePicture(null, null, (data, cam) -> {
                Log.d(TAG, "[capture/callback] Nhận dữ liệu ảnh, bắt đầu lưu...");
                File file = new File(getContext().getCacheDir(),
                        "photo_" + System.currentTimeMillis() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                    fos.flush();
                    Log.d(TAG, "[capture/callback] Lưu ảnh OK: " + file.getAbsolutePath());

                    WritableMap map = Arguments.createMap();
                    map.putString("uri", "file://" + file.getAbsolutePath());
                    
                    emitPictureSaved(map);
                    Log.d(TAG, "📸 [capture/callback] Emit onPictureSaved");
                    promise.resolve(map);

                } catch (IOException e) {
                    Log.e(TAG, "⛔ [capture/callback] Lỗi lưu ảnh: " + e.getMessage(), e);
                    emitError("Error saving picture: " + e.getMessage());
                    promise.reject(e);
                }

                try {
                    Log.d(TAG, "[capture/callback] restart preview sau chụp...");
                    cam.startPreview();
                    isPreviewActive = true;
                } catch (Exception ex) {
                    isPreviewActive = false;
                    Log.e(TAG, "⛔ [capture/callback] Failed to restart preview: " + ex.getMessage(), ex);
                    promise.reject(ex);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "⛔ [capture] Lỗi takePicture(): " + e.getMessage(), e);
            emitError("capture() failed: " + e.getMessage());
            promise.reject(e);
        }
    }
    // endregion

    // region ===== TextureView Callbacks (Google Camera1 lifecycle) =====
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if(camera != null) {
            Log.d(TAG, "[onSurfaceTextureAvailable] da co camera roi, return...");
            return;
        }
        Log.d(TAG, "[onSurfaceTextureAvailable] Surface có sẵn: w=" + width + ", h=" + height);
        surfaceWasDestroyed = false;
        isSurfaceReady = true;

        // Theo Google: nếu surface quay lại sau khi destroy → start()/restart preview
        startBgThread();
        Log.d(TAG, "[onSurfaceTextureAvailable] Đợi 300–500ms cho surface ổn định rồi gắn preview...");
        bgHandler.postDelayed(() -> {
            if (surfaceWasDestroyed) {
                Log.w(TAG, "[onSurfaceTextureAvailable] Bỏ qua, surface vừa bị destroy lại");
                return;
            }
            if (camera == null) {
                Log.d(TAG, "[onSurfaceTextureAvailable] camera==null → openCameraSafe()");
                openCameraSafe();
            } else {
                Log.d(TAG, "[onSurfaceTextureAvailable] camera!=null → restartPreviewIfNeeded()");
                restartPreviewIfNeeded();
            }
        }, 500);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "[onSurfaceTextureSizeChanged] Kích thước mới: w=" + width + ", h=" + height + ". Sẽ gắn lại preview.");
        // Giống tinh thần updateSurface() của Camera1: chỉ cần gắn lại preview là được.
        restartPreviewIfNeeded();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.d(TAG, "[onSurfaceTextureDestroyed] Surface bị destroy → chỉ dừng preview, KHÔNG release camera ngay");
        surfaceWasDestroyed = true;
        isSurfaceReady = false;

        if (camera != null && bgHandler != null) {
            bgHandler.post(() -> {
                try {
                    camera.stopPreview();
                    isPreviewActive = false;
                    Log.d(TAG, "[onSurfaceTextureDestroyed/bg] stopPreview() OK, giữ camera mở để restart sau");
                } catch (Exception e) {
                    Log.e(TAG, "[onSurfaceTextureDestroyed/bg] stopPreview() lỗi: " + e.getMessage());
                }
            });
        }
        return true; // vẫn giữ surface release logic
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Khi khung hình đầu tiên render → đảm bảo preview đang chạy
        if (!isPreviewActive && camera != null && isSurfaceReady) {
            Log.d(TAG, "[onSurfaceTextureUpdated] Nhận frame đầu tiên nhưng isPreviewActive=false → restartPreviewIfNeeded()");
            restartPreviewIfNeeded();
        } else {
            Log.d(TAG, "[onSurfaceTextureUpdated] Frame cập nhật. isPreviewActive=" + isPreviewActive
                    + ", camera=" + (camera != null) + ", isSurfaceReady=" + isSurfaceReady);
        }
    }
    // endregion

    // region ===== View attach/detach =====
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Log.d(TAG, "[onAttachedToWindow] View attach vào window. Khởi động bg thread nếu cần.");
        Log.d(TAG, "[debug] onAttachedToWindow() gọi lại, camera=" + (camera != null));
        startBgThread();
        if (textureView != null && textureView.isAvailable()) {
            Log.d(TAG, "[onAttachedToWindow] Texture đã available → đảm bảo preview.");
            if (camera == null) openCameraSafe(); else restartPreviewIfNeeded();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d(TAG, "[onDetachedFromWindow] View detach khỏi window → đóng camera + dừng thread.");
        closeCameraSafe();
        stopBgThread();
    }
    // endregion

    // region ===== React Events =====
    private void emitCameraReady() {
        Log.d(TAG, "[emitCameraReady] Phát sự kiện onCameraReady → RN");
        WritableMap event = Arguments.createMap();
        event.putString("status", "ready");
        sendEvent("onCameraReady", event);
    }

    private void emitError(String message) {
        Log.e(TAG, "[emitError] error=" + message);
        WritableMap event = Arguments.createMap();
        event.putString("error", message);
        sendEvent("onError", event);
    }

    private void emitPictureSaved(WritableMap data) {
        Log.d(TAG, "[emitPictureSaved] Phát sự kiện onPictureSaved → RN, uri=" + data.getString("uri"));
        sendEvent("onPictureSaved", data);
    }

    private void sendEvent(String eventName, @Nullable WritableMap event) {
        Log.d(TAG, "[sendEvent] event=" + eventName + ", hasData=" + (event != null));
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(getId(), eventName, event);
    }
    // endregion
}
