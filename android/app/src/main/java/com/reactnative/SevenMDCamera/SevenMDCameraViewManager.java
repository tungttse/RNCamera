package com.reactnative.SevenMDCamera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;

import java.util.Map;

public class SevenMDCameraViewManager extends SimpleViewManager<SevenMDCameraView> {
    public static final String REACT_CLASS = "SevenMDCamera";

    @NonNull @Override public String getName() { return REACT_CLASS; }

    @NonNull @Override
    protected SevenMDCameraView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new SevenMDCameraView(reactContext);
    }

    @Nullable
    @Override
    public Map<String, Integer> getCommandsMap() {
        return MapBuilder.of("takePicture", 1);
    }

    @Override
    public void receiveCommand(@NonNull SevenMDCameraView view, String commandId, @Nullable com.facebook.react.bridge.ReadableArray args) {
        if ("takePicture".equals(commandId) || "1".equals(commandId)) {
            view.takePicture();
        }
    }

    @Nullable
    @Override
    public Map getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.of(
            "onCameraReady", MapBuilder.of("registrationName", "onCameraReady"),
            "onPictureSaved", MapBuilder.of("registrationName", "onPictureSaved"),
            "onError", MapBuilder.of("registrationName", "onError")
        );
    }
}
