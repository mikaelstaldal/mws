package nu.staldal.mws

import android.os.Bundle
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var myPassClient: MyPassClient
    private lateinit var myPassStatus: MyPassStatusIndicator
    private lateinit var compatibility: ProviderCompatibility
    private lateinit var compatibilityMessage: TextView
    private lateinit var providerSettings: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myPassClient = MyPassClient(this)
        myPassClient.restoreIdentity(savedInstanceState)
        myPassStatus = MyPassStatusIndicator(this)
        compatibility = ProviderCompatibility(
            AndroidProviderSnapshotSource(this),
            ProviderSelfTestStore { null },
            debugOverride = BuildConfig.DEBUG_PROVIDER_OVERRIDE,
        )
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@MainActivity).apply { setText(R.string.empty_state) })
            compatibilityMessage = TextView(this@MainActivity)
            addView(compatibilityMessage)
            providerSettings = Button(this@MainActivity).apply {
                setText(R.string.provider_settings)
                setOnClickListener {
                    compatibility.current().key?.packageName?.let { packageName ->
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:$packageName")))
                        } catch (_: ActivityNotFoundException) {
                            compatibilityMessage.setText(R.string.provider_settings_unavailable)
                        }
                    }
                }
            }
            addView(providerSettings)
            addView(myPassStatus)
        })
    }

    override fun onResume() {
        super.onResume()
        myPassStatus.refresh(myPassClient)
        val decision = compatibility.current()
        compatibilityMessage.text = if (decision.compatible) "" else getString(
            R.string.provider_incompatible,
            decision.key?.packageName ?: getString(R.string.provider_unknown),
            decision.key?.version ?: getString(R.string.provider_unknown),
        )
        providerSettings.isEnabled = !decision.compatible && decision.key != null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        myPassClient.saveIdentity(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        myPassClient.close()
        super.onDestroy()
    }
}
