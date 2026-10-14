package com.tvhub.media

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.tvhub.media.core.Perms
import com.tvhub.media.core.Prefs
import com.tvhub.media.ui.common.MenuKeyHandler
import com.tvhub.media.ui.home.HomeFragment

/**
 * Single host activity. Back order:
 *   open dialog (closes itself) → screen-level handlers (e.g. folder → parent) → previous screen
 *   → on Home: press Back twice to exit (or nothing, when running as the TV's home screen).
 */
class MainActivity : AppCompatActivity() {

    private var pendingPermission: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val cb = pendingPermission
        pendingPermission = null
        cb?.invoke(Perms.hasMedia(this))
    }
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, HomeFragment())
                .commit()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val fm = supportFragmentManager
                if (fm.backStackEntryCount > 0) {
                    fm.popBackStack()
                    return
                }
                if (isHomeLaunch()) return // never leave the user with no home screen
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackAt < 2000) {
                    finish()
                } else {
                    lastBackAt = now
                    Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Pressing Home while we're the launcher: go back to our home screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    private fun isHomeLaunch(): Boolean = intent?.hasCategory(Intent.CATEGORY_HOME) == true

    fun navigate(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .apply {
                if (Prefs.animations) {
                    setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)
                }
            }
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun ensureMediaPermission(callback: (Boolean) -> Unit) {
        if (Perms.hasMedia(this)) {
            callback(true)
            return
        }
        pendingPermission = callback
        try {
            permLauncher.launch(Perms.mediaPermissions())
        } catch (e: Exception) {
            pendingPermission = null
            callback(false)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                val f = supportFragmentManager.findFragmentById(R.id.container)
                if (f is MenuKeyHandler && f.onMenuKey()) return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
