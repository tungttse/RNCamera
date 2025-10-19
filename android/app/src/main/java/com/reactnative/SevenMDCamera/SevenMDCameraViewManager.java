package com.reactnative.SevenMDCamera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import java.util.Map;
import java.util.HashMap;

/**
 * React Native ViewManager cho SevenMDCameraView (Camera1)
 */
public class SevenMDCameraViewManager extends SimpleViewManager<SevenMDCameraView> {
    private static final int COMMAND_CAPTURE = 1;

    private final ReactApplicationContext reactContext;

    public SevenMDCameraViewManager(ReactApplicationContext reactContext) {
        this.reactContext = reactContext;
    }

    @NonNull
    @Override
    public String getName() {
        return "SevenMDCamera";
    }

    @NonNull
    @Override
    protected SevenMDCameraView createViewInstance(@NonNull ThemedReactContext reactContext) {
        SevenMDCameraView view = new SevenMDCameraView(reactContext);
        SevenMDCameraModule.setCameraViewRef(view);
        return view;
    }

    /**
     * Map các lệnh từ JS → native
     */
    @Nullable
    @Override
    public Map<String, Integer> getCommandsMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("takePhoto", COMMAND_CAPTURE);
        return map;
    }

  
    /**
     * Các event native gửi sang JS
     */
    @Nullable
    @Override
    public Map getExportedCustomDirectEventTypeConstants() {
        Map<String, Map<String, String>> events = new HashMap<>();
        events.put("onCameraReady", Map.of("registrationName", "onCameraReady"));
        events.put("onPictureSaved", Map.of("registrationName", "onPictureSaved"));
        events.put("onError", Map.of("registrationName", "onError"));
        return (Map) events;
    }

    /**
     * Các props (nếu cần trong tương lai)
     */
    @ReactProp(name = "cameraId", defaultInt = 0)
    public void setCameraId(SevenMDCameraView view, int cameraId) {
        // Bạn có thể mở rộng để chọn camera trước/sau
    }

    @ReactProp(name = "autoStart", defaultBoolean = true)
    public void setAutoStart(SevenMDCameraView view, boolean autoStart) {
        // Hiện chưa cần — chỉ placeholder để tương lai mở rộng
    }
}
