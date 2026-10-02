package il.org.hatzolahair.crm

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hai_settings", Context.MODE_PRIVATE)

    var biometricLock: Boolean
        get() = sp.getBoolean("biometric_lock", false)
        set(value) = sp.edit { putBoolean("biometric_lock", value) }

    /** On by default: this app shows patient information. */
    var secureScreen: Boolean
        get() = sp.getBoolean("secure_screen", true)
        set(value) = sp.edit { putBoolean("secure_screen", value) }
}
