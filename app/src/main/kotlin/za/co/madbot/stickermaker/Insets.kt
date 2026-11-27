package za.co.madbot.stickermaker

import android.content.res.Configuration
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Apps that target Android 15+ draw edge to edge, so the status and navigation bars sit on top of the content. This keeps the
 * toolbar below the status bar (its colour still runs under it) and everything else clear of the navigation bar, display cutouts
 * and the keyboard.
 */
fun AppCompatActivity.fitSystemBars(root: View, toolbar: View) {
    // Dark icons on the light theme, light icons on the dark theme (edge to edge no longer picks this for us).
    val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, root).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    val toolbarTop = toolbar.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
        val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        toolbar.setPadding(toolbar.paddingLeft, toolbarTop + bars.top, toolbar.paddingRight, toolbar.paddingBottom)
        root.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}
