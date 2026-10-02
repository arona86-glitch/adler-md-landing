package il.org.hatzolahair.crm

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch

/** The "More" bottom sheet: every CRM section that doesn't fit the tab bar, plus app options. */
class MoreSheet(
    private val activity: Activity,
    private val prefs: Prefs,
    private val actions: Actions,
) {
    interface Actions {
        fun openPath(path: String)
        fun reload()
        fun openInBrowser()
        fun copyLink()
        fun shareLink()
        fun signOut()
        fun setBiometricLock(enabled: Boolean): Boolean
        fun setSecureScreen(enabled: Boolean)
    }

    private class Destination(@StringRes val label: Int, @DrawableRes val icon: Int, val path: String)

    private val destinations = listOf(
        Destination(R.string.more_new_case, R.drawable.ic_new_case, "/cases/new"),
        Destination(R.string.more_staff, R.drawable.ic_staff, "/staff"),
        Destination(R.string.more_equipment, R.drawable.ic_equipment, "/equipment"),
        Destination(R.string.more_logistics, R.drawable.ic_logistics, "/logistics"),
        Destination(R.string.more_flight_day, R.drawable.ic_flight, "/flight-day-bookings"),
        Destination(R.string.more_tools, R.drawable.ic_tools, "/tools"),
        Destination(R.string.more_settings, R.drawable.ic_settings, "/settings"),
        Destination(R.string.more_admin, R.drawable.ic_admin, "/admin/users"),
    )

    fun show() {
        val inflater = LayoutInflater.from(activity)
        val dialog = BottomSheetDialog(activity)
        val content = inflater.inflate(R.layout.sheet_more, null, false)
        val go = content.findViewById<ViewGroup>(R.id.sectionGo)
        val app = content.findViewById<ViewGroup>(R.id.sectionApp)

        for (d in destinations) {
            addRow(inflater, go, d.icon, d.label) {
                dialog.dismiss()
                actions.openPath(d.path)
            }
        }

        val lockRow = addRow(
            inflater, app, R.drawable.ic_lock, R.string.more_biometric,
            hint = R.string.more_biometric_hint, checked = prefs.biometricLock,
        ) { /* handled by switch listener below */ }
        val secureRow = addRow(
            inflater, app, R.drawable.ic_phone_shield, R.string.more_secure_screen,
            hint = R.string.more_secure_screen_hint, checked = prefs.secureScreen,
        ) { }
        bindSwitch(lockRow) { wanted ->
            val applied = actions.setBiometricLock(wanted)
            if (!applied) lockRow.findViewById<MaterialSwitch>(R.id.rowSwitch).isChecked = false
        }
        bindSwitch(secureRow) { wanted -> actions.setSecureScreen(wanted) }

        addRow(inflater, app, R.drawable.ic_refresh, R.string.more_reload) { dialog.dismiss(); actions.reload() }
        addRow(inflater, app, R.drawable.ic_open_browser, R.string.more_open_browser) { dialog.dismiss(); actions.openInBrowser() }
        addRow(inflater, app, R.drawable.ic_copy, R.string.more_copy_link) { dialog.dismiss(); actions.copyLink() }
        addRow(inflater, app, R.drawable.ic_share, R.string.more_share_link) { dialog.dismiss(); actions.shareLink() }
        addRow(inflater, app, R.drawable.ic_logout, R.string.more_sign_out) { dialog.dismiss(); actions.signOut() }

        dialog.setContentView(content)
        dialog.show()
    }

    /** Makes the whole row toggle its switch. */
    private fun bindSwitch(row: View, onChange: (Boolean) -> Unit) {
        val toggle = row.findViewById<MaterialSwitch>(R.id.rowSwitch)
        toggle.isClickable = false
        row.setOnClickListener {
            toggle.isChecked = !toggle.isChecked
            onChange(toggle.isChecked)
        }
    }

    private fun addRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        @DrawableRes icon: Int,
        @StringRes label: Int,
        @StringRes hint: Int? = null,
        checked: Boolean? = null,
        onClick: () -> Unit,
    ): View {
        val row = inflater.inflate(R.layout.item_more_row, parent, false)
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.rowTitle).setText(label)
        if (hint != null) {
            row.findViewById<TextView>(R.id.rowHint).apply {
                setText(hint)
                visibility = View.VISIBLE
            }
        }
        if (checked != null) {
            row.findViewById<MaterialSwitch>(R.id.rowSwitch).apply {
                visibility = View.VISIBLE
                isChecked = checked
            }
        }
        row.setOnClickListener { onClick() }
        parent.addView(row)
        return row
    }
}
