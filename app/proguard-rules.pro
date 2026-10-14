# FFmpeg audio renderer is loaded by reflection from DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
# Keep the home-screen alias component name stable.
-keep class com.tvhub.media.MainActivity
