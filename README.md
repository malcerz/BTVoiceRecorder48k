# BT Dyktafon 48k — standalone test

Audio-only Android diagnostic application. No camera permission and no video track.

- Bluetooth mic only: choose BLE headset or HFP/SCO microphone; fail closed if recorder routes to phone.
- Four Android `MediaRecorder.AudioSource` modes: MIC (default), VOICE_COMMUNICATION, VOICE_RECOGNITION, UNPROCESSED.
- Request AAC-LC mono 48 kHz at 192 kb/s, save `.m4a` in MediaStore `Music/BT Dyktafon 48k`.
- Show `MediaRecorder.getRoutedDevice`, client sample rate, Android input format sample rate, available BT sample rates and Android audio effects.
- Stop/delete recording automatically on BT route loss. The 48 kHz file does not prove 48 kHz radio microphone quality; analyze the spectrum.
- Audio is recorded only while the app is on screen. No background recording, no camera.

## Test

Connect Huawei FreeBuds Pro 5, enable call profile. Grant Microphone and Nearby Devices access. Use "SPRAWDZ BT" to see microphone inputs. Choose one mode, record 15 seconds, stop and copy M4A. Compare against BTFrontCamTest v0.5.

## Build

Android Gradle Plugin 8.7.3 / Gradle 8.9 / JDK 17, `gradle :app:assembleDebug`.
