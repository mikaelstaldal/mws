package nu.staldal.mws

import android.content.Context
import androidx.appcompat.widget.AppCompatTextView

/** Read-only status for the app editor. Refresh when the editor resumes. */
class MyPassStatusIndicator(context: Context) : AppCompatTextView(context) {
    init {
        isFocusable = false
        isClickable = false
    }

    fun refresh(client: MyPassClient) {
        setText(
            if (client.availability() == MyPassClient.Availability.AVAILABLE) {
                R.string.mypass_available
            } else {
                R.string.mypass_unavailable
            },
        )
    }
}
