# FFmpeg audio renderer is loaded by reflection from DefaultRenderersFactory.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
# Keep the home-screen alias component name stable.
-keep class com.tvhub.media.MainActivity
# YouTube player bridge methods are called from JavaScript by name.
-keepclassmembers class com.tvhub.media.player.YouTubePlayerActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
