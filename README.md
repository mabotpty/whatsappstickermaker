# Sticker Maker

Personal WhatsApp sticker maker for Android (sideload only). Works with WhatsApp and WhatsApp Business.

- Import jpg, png, webp, gif, heic, avif, bmp and animated gif / animated webp (also via Share).
- Edit: crop, rotate, flip, background removal (ML Kit, on-device), erase / restore brush, text, white outline.
- Static stickers are exported as 512x512 WebP under 100 KB; animated ones as animated WebP under 500 KB / 10 s
  (quality and frame count are reduced automatically to fit).
- Multiple packs (3-30 stickers each; a pack is all-static or all-animated, a WhatsApp rule).

## Build

Needs the JDK and Android SDK that ship with Android Studio (AGP 9, Gradle 9.1, NDK 28 are fetched automatically).

    JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug

APK: `app/build/outputs/apk/debug/app-debug.apk` (arm64 only; add `-PwithX86` for the PC emulator).

Animated WebP encoding/decoding is a small JNI bridge (`app/src/main/cpp/webp_jni.cpp`) over a vendored libwebp 1.5.0.
The WhatsApp content provider follows https://github.com/WhatsApp/stickers/tree/main/Android.

Background removal downloads a model through Google Play services the first time it is used.
