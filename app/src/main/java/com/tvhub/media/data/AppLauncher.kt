package com.tvhub.media.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import com.tvhub.media.R
import com.tvhub.media.core.Prefs

data class AppInfo(val packageName: String, val label: String, val icon: Drawable?, val banner: Drawable?)

data class StreamingService(
    val id: String,
    val labelRes: Int,
    val knownPackages: List<String>,
    val keywords: List<String>,
    val iconRes: Int
)

/**
 * Opens the official installed apps. No scraping, no bypassing logins/DRM.
 * Watcho/Dodear package names vary by region/build, so they are found by app name,
 * and the user can pick the right app once (remembered).
 */
object AppLauncher {
    val services = listOf(
        StreamingService(
            "youtube", R.string.tile_youtube,
            listOf("com.google.android.youtube.tv", "com.google.android.youtube"),
            listOf("youtube"), R.drawable.ic_play_box
        ),
        StreamingService("watcho", R.string.tile_watcho, emptyList(), listOf("watcho"), R.drawable.ic_tv),
        StreamingService("dodear", R.string.tile_dodear, emptyList(), listOf("dodear"), R.drawable.ic_film)
    )

    fun launchIntent(pm: PackageManager, pkg: String): Intent? =
        pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)

    fun isInstalled(pm: PackageManager, pkg: String): Boolean = launchIntent(pm, pkg) != null

    /** Call off the main thread. Returns the package to open, or null if not installed. */
    fun resolve(context: Context, service: StreamingService, apps: List<AppInfo>): String? {
        val pm = context.packageManager
        Prefs.appPackage(service.id)?.let { saved ->
            if (isInstalled(pm, saved)) return saved
            Prefs.setAppPackage(service.id, null) // app was uninstalled
        }
        service.knownPackages.firstOrNull { isInstalled(pm, it) }?.let { return it }
        // Prefer an exact name match ("YouTube"), then a partial match, never "YouTube Music/Kids".
        val exact = apps.firstOrNull { a -> service.keywords.any { a.label.equals(it, ignoreCase = true) } }
        if (exact != null) return exact.packageName
        return apps.firstOrNull { a ->
            service.keywords.any { k ->
                (a.label.contains(k, true) || a.packageName.contains(k, true)) &&
                    !a.label.contains("music", true) && !a.label.contains("kids", true)
            }
        }?.packageName
    }

    /** Call off the main thread. Icons/banners only when needed (they cost memory). */
    @Suppress("DEPRECATION")
    fun installedApps(context: Context, withIcons: Boolean): List<AppInfo> {
        val pm = context.packageManager
        val result = LinkedHashMap<String, AppInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || result.containsKey(pkg)) continue
                result[pkg] = AppInfo(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    icon = if (withIcons) runCatching { ri.loadIcon(pm) }.getOrNull() else null,
                    banner = if (withIcons) runCatching { ri.activityInfo.loadBanner(pm) }.getOrNull() else null
                )
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    fun appIcon(context: Context, pkg: String, preferBanner: Boolean): Drawable? = runCatching {
        val pm = context.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        if (preferBanner) ai.loadBanner(pm) ?: ai.loadIcon(pm) else ai.loadIcon(pm)
    }.getOrNull()

    fun launch(context: Context, pkg: String): Boolean {
        val intent = launchIntent(context.packageManager, pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    fun openStoreSearch(context: Context, query: String): Boolean {
        val q = Uri.encode(query)
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q&c=apps")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$q&c=apps"))
        )
        for (i in attempts) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            }
        }
        return false
    }
}
