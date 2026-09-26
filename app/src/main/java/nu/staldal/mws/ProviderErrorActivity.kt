package nu.staldal.mws

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ProviderErrorActivity : AppCompatActivity() {
    private lateinit var message: TextView
    private lateinit var settingsButton: MaterialButton
    private val services by lazy { ShellServices.shared(this) }
    private val compatibility get() = services.compatibility

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        message = TextView(this).apply {
            id = R.id.provider_error_message
        }
        settingsButton = MaterialButton(this).apply {
            setText(R.string.provider_settings)
            setOnClickListener {
                val packageName = compatibility.current().key?.packageName ?: return@setOnClickListener
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (_: ActivityNotFoundException) {
                    message.setText(R.string.provider_settings_unavailable)
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                view.setPadding(32 + safe.left, 32 + safe.top, 32 + safe.right, 32 + safe.bottom)
                insets
            }
            addView(message)
            addView(settingsButton)
        })
    }

    override fun onResume() {
        super.onResume()
        message.setText(R.string.provider_self_test_running)
        settingsButton.isEnabled = false
        lifecycleScope.launch {
            try {
                services.awaitStartup()
                showDecision()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.setText(R.string.provider_check_failed)
            }
        }
    }

    private fun showDecision() {
        val decision = compatibility.current()
        if (decision.compatible) {
            finish()
            return
        }
        message.text = getString(R.string.provider_incompatible,
            decision.key?.packageName ?: getString(R.string.provider_unknown),
            decision.key?.version ?: getString(R.string.provider_unknown))
        settingsButton.isEnabled = decision.key != null
    }
}
