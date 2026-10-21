# ML Kit discovers its components by reflection (no-arg constructors). Without these, R8 strips
# them and text recognition fails in release builds ("NoSuchMethodException: ...Registrar.<init>").
-keep class com.google.mlkit.**.*Registrar { <init>(); }
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
