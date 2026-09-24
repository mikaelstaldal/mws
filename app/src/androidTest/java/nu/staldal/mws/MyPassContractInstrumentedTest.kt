package nu.staldal.mws

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyPassContractInstrumentedTest {
    @Test fun requestUsesExplicitPackageActionAndOnlyRequiredExtras() {
        val form = MyPassClient.requestIntent("https://example.com/login")
        assertEquals(MyPassClient.PACKAGE, form.`package`)
        assertEquals(MyPassClient.ACTION, form.action)
        assertEquals("https://example.com/login", form.getStringExtra(MyPassClient.EXTRA_URL))
        assertFalse(form.hasExtra(MyPassClient.EXTRA_REALM))
        assertEquals(1, form.extras!!.keySet().size)

        val basic = MyPassClient.requestIntent("https://example.com/private", "members")
        assertEquals("members", basic.getStringExtra(MyPassClient.EXTRA_REALM))
        assertEquals(2, basic.extras!!.keySet().size)
    }

    @Test fun resultRequiresOkAndBothStringExtras() {
        val data = Intent()
            .putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "alice")
            .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, "secret")
        assertEquals(MyPassClient.Outcome.NoCredential,
            MyPassClient.parseResult(ActivityResult(Activity.RESULT_CANCELED, data)))
        assertEquals(MyPassClient.Outcome.NoCredential,
            MyPassClient.parseResult(ActivityResult(Activity.RESULT_OK, Intent())))
        assertEquals(MyPassClient.Outcome.NoCredential,
            MyPassClient.parseResult(ActivityResult(Activity.RESULT_OK,
                Intent().putExtra(MyPassClient.EXTRA_RESULT_USERNAME, "alice"))))
        assertEquals(MyPassClient.Outcome.NoCredential,
            MyPassClient.parseResult(ActivityResult(Activity.RESULT_OK,
                Intent().putExtra(MyPassClient.EXTRA_RESULT_USERNAME, 1)
                    .putExtra(MyPassClient.EXTRA_RESULT_PASSWORD, "secret"))))

        val credential = MyPassClient.parseResult(ActivityResult(Activity.RESULT_OK, data))
            as MyPassClient.Outcome.Credential
        assertEquals("alice", String(credential.username))
        assertEquals("secret", String(credential.password))
        credential.clear()
        assertTrue(credential.username.all { it == '\u0000' })
        assertTrue(credential.password.all { it == '\u0000' })
    }

    @Test fun signaturePermissionIsDeclared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(MyPassClient.PERMISSION))
    }
}
