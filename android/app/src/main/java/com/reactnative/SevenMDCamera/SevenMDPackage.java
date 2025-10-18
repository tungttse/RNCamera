package com.reactnative.SevenMDCamera;

import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.uimanager.ViewManager;
import com.facebook.react.bridge.ReactApplicationContext;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SevenMDPackage implements ReactPackage {
  @Override public List<NativeModule> createNativeModules(ReactApplicationContext context) {
    return Collections.emptyList();
  }

  @Override public List<ViewManager> createViewManagers(ReactApplicationContext context) {
    return Arrays.<ViewManager>asList(new SevenMDCameraViewManager(context));
  }
}
