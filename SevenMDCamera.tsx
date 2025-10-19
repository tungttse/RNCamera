import React, { forwardRef, useImperativeHandle, useRef, memo } from "react";
import { requireNativeComponent, NativeModules } from "react-native";

const NativeCamera = requireNativeComponent("SevenMDCamera");
const { SevenMDCameraModule } = NativeModules;

export const SevenMDCamera = memo(
  forwardRef((props, ref) => {
    const nativeRef = useRef(null);

    useImperativeHandle(ref, () => ({
      async takePhoto() {
        try {
          const result = await SevenMDCameraModule.capture();
          console.log("🎉 Capture result:", result);
          return result; // { uri: "file://..." }
        } catch (err) {
          console.error("Capture error:", err);
          throw err;
        }
      },
    }));

    return <NativeCamera ref={nativeRef} {...props} />;
  }),
  () => true
);
