package com.sidephone.aviary

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sidephone.aviary.imessage.ImessageNative
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the iMessage native engine. Exercises the highest-risk
 * on-device pieces WITHOUT needing Apple credentials or a validation-data relay:
 *   - the 28 MB libaviary_imessage.so loads
 *   - Apple's ADI libs load via android_loader on real arm64 hardware
 *   - APS connect runs activate() against albert.apple.com with the legacy FairPlay cert
 *
 * Registration proper (nativeRegister) still needs a validation-data source and is not
 * attempted here. Read the result in logcat under tag "ImessageSmoke".
 */
@RunWith(AndroidJUnit4::class)
class ImessageSmokeTest {

    @Test
    fun nativeInit_loadsEngineAndAdi() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val libDir = ImessageNative.prepareLibDir(ctx)
        val configDir = ImessageNative.configDir(ctx)
        Log.i(TAG, "libDir=$libDir configDir=$configDir")

        // Placeholder relay coords: enough to build the config + open APS. Registration
        // (which would use host/code for validation data) is not attempted.
        val plist = ImessageNative.relayConfigPlist(host = "https://relay.invalid", code = "smoke")
        Log.i(TAG, "config plist:\n$plist")

        val result = ImessageNative.nativeInit(plist, libDir, configDir)
        Log.i(TAG, "nativeInit => $result")

        // We don't assert ok=true (APS/activation may fail without a real setup); we assert
        // the native call RETURNED a well-formed JSON response — i.e. the lib + ADI loaded
        // and the Rust side ran without crashing.
        assertTrue("native returned a response", result.trim().startsWith("{"))
    }

    private companion object {
        const val TAG = "ImessageSmoke"
    }
}
