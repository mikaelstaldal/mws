package nu.staldal.mws

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Temporary destinations until the editor task provides its activities. */
class PlaceholderActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = when (intent.action) {
            ACTION_ADD -> R.string.add_unavailable
            ACTION_EDIT -> R.string.editor_unavailable
            else -> R.string.unknown_action
        }
        setContentView(TextView(this).apply {
            setText(label)
            textSize = 18f
            setPadding(32, 32, 32, 32)
        })
    }

    companion object {
        const val ACTION_ADD = "nu.staldal.mws.ADD"
        const val ACTION_EDIT = "nu.staldal.mws.EDIT"
        const val EXTRA_APP_ID = "nu.staldal.mws.APP_ID"
    }
}
