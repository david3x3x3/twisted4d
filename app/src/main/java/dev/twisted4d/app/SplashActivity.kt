package dev.twisted4d.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

/** Shows an enlarged app icon full-screen for a couple seconds on launch, then hands off to
 * [MainActivity] and finishes -- purely cosmetic, no puzzle/gamepad setup happens here, so it's
 * safe to skip entirely on process-death restarts of [MainActivity] itself (this is only ever
 * the launcher entry point). */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val iconSize = (220 * resources.displayMetrics.density).toInt()
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#101018"))
            addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        }
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DURATION_MS)
    }

    companion object {
        private const val SPLASH_DURATION_MS = 2000L
    }
}
