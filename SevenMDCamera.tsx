import React, { forwardRef, useImperativeHandle, useRef } from "react";
import { requireNativeComponent, UIManager, findNodeHandle } from "react-native";

const NativeCamera = requireNativeComponent("SevenMDCamera");

export const SevenMDCamera = forwardRef((props: any, ref) => {
  const nativeRef = useRef(null);
  useImperativeHandle(ref, () => ({
    takePicture: () => {
      const viewId = findNodeHandle(nativeRef.current);
      // @ts-ignore
      UIManager.dispatchViewManagerCommand(
        viewId,
        // @ts-ignore
        UIManager.getViewManagerConfig("SevenMDCamera").Commands.takePicture,
        []
      );
    },
  }));
  return <NativeCamera ref={nativeRef} {...props} />;
});
