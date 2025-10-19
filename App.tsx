/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */
import React from 'react';
import { NewAppScreen } from '@react-native/new-app-screen';
import { StatusBar, StyleSheet, useColorScheme, View, Text, TouchableOpacity, Alert } from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import { Camera, CameraType } from 'react-native-camera-kit';
import { SevenMDCamera } from './SevenMDCamera';

function App() {
  const isDarkMode = useColorScheme() === 'dark';

  return (
    <SafeAreaProvider>
      <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
      <AppContent />
    </SafeAreaProvider>
  );
}

function AppContent() {
  const safeAreaInsets = useSafeAreaInsets();
  const cameraRef = React.useRef(null);

  const handleCapture = () => {
    if (cameraRef.current) {
      // @ts-ignore
      Alert.alert("capture");
      cameraRef.current.takePhoto();
    }
  };

  return (
    <View style={styles.container}>
      <View style={styles.textContainer}>
        <Text style={styles.welcomeText}>Welcome to React Native!</Text>
        <Text style={styles.subtitleText}>This is your custom text on the screen</Text>
      </View>
      <View style={styles.buttonContainer}>
        <TouchableOpacity style={styles.captureButton} onPress={handleCapture}>
          <Text style={styles.captureButtonText}>Capture Photo</Text>
        </TouchableOpacity>
      </View>
      <SevenMDCamera
        ref={cameraRef}
        style={{ flex: 1 }}
        onCameraReady={() => console.log("ready")}
        onPictureSaved={(e) => Alert.alert("saved:", e.nativeEvent.path)}
      />
      
    
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  textContainer: {
    padding: 20,
    alignItems: 'center',
    backgroundColor: '#f0f0f0',
  },
  welcomeText: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#333',
    marginBottom: 10,
  },
  subtitleText: {
    fontSize: 16,
    color: '#666',
    textAlign: 'center',
  },
  buttonContainer: {
    padding: 20,
    alignItems: 'center',
    backgroundColor: '#f8f8f8',
  },
  captureButton: {
    backgroundColor: '#007AFF',
    paddingHorizontal: 30,
    paddingVertical: 15,
    borderRadius: 25,
    elevation: 3,
    shadowColor: '#000',
    shadowOffset: {
      width: 0,
      height: 2,
    },
    shadowOpacity: 0.25,
    shadowRadius: 3.84,
  },
  captureButtonText: {
    color: 'white',
    fontSize: 18,
    fontWeight: 'bold',
  },
});

export default App;
