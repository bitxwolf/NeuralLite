# Keep JNI native methods and callback interfaces invoked from llama_bridge.cpp
-keep class com.neurallite.app.engine.LlamaEngine {
    native <methods>;
}
-keep interface com.neurallite.app.engine.LlamaEngine$TokenCallback {
    void onToken(byte[]);
}
-keepclassmembers class * implements com.neurallite.app.engine.LlamaEngine$TokenCallback {
    void onToken(byte[]);
}
