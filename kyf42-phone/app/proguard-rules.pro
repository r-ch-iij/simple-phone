# KYF42 SimplePhone ProGuard Rules

# ---- ZXing (QR code scanning) ----
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# ---- JNI / baresip native bridge ----
-keep class io.github.r_ch_iij.simplephone.NativeSip { *; }
-keep class io.github.r_ch_iij.simplephone.NativeSip$Callback { *; }

# ---- SIP callback interface (invoked from JNI) ----
-keepclassmembers class io.github.r_ch_iij.simplephone.NativeSip$Callback {
    *;
}
