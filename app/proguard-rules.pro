# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android.txt

# Keep Firebase models
-keepclassmembers class com.example.locationmap.model.** {
    *;
}

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
