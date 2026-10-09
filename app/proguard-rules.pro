# Prepared for enabling R8 (isMinifyEnabled) later. NOT verified on a device yet.
# sherpa-onnx calls into Kotlin/Java classes from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# MediaPipe tasks use reflection and protobuf internals.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
