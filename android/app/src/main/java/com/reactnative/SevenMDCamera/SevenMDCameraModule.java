package com.reactnative.SevenMDCamera;

import android.util.Log;
import com.facebook.react.bridge.*;
import java.io.*;


public class SevenMDCameraModule extends ReactContextBaseJavaModule {
    private static final String TAG = "SevenMDCameraModule";
    private static SevenMDCameraView cameraViewRef; // static ref từ view (được set trong ViewManager)

    public SevenMDCameraModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return "SevenMDCameraModule";
    }

    // Được gọi từ View để gắn ref camera hiện tại
    public static void setCameraViewRef(SevenMDCameraView ref) {
        cameraViewRef = ref;
    }

    @ReactMethod
    public void capture(Promise promise) {
        if (cameraViewRef == null) {
            promise.reject("E_NO_CAMERA", "Camera view is not attached");
            return;
        }
        cameraViewRef.capture(promise);
    }
}
