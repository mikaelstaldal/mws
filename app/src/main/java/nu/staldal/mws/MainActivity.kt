package nu.staldal.mws

import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var myPassClient: MyPassClient
    private lateinit var myPassStatus: MyPassStatusIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myPassClient = MyPassClient(this)
        myPassClient.restoreIdentity(savedInstanceState)
        myPassStatus = MyPassStatusIndicator(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(this@MainActivity).apply { setText(R.string.empty_state) })
            addView(myPassStatus)
        })
    }

    override fun onResume() {
        super.onResume()
        myPassStatus.refresh(myPassClient)
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
