package com.netnaija.settings

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.netnaija.NetNaijaPlugin
import kotlin.math.max

class NetNaijaSettingsFragment(
    private val plugin: NetNaijaPlugin,
    private val sharedPref: SharedPreferences
) : DialogFragment() {

    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com"
    )

    private val res: Resources = plugin.resources ?: throw Exception("Unable to access plugin resources")

    private fun findView(view: View, name: String): View {
        val id = res.getIdentifier(name, "id", "com.netnaija")
        if (id == 0) throw Exception("View ID $name not found.")
        return view.findViewById(id) ?: throw Exception("View $name not found.")
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog ?: return
        val window = dialog.window ?: return
        val metrics = resources.displayMetrics
        val maxDialogWidth = (500f * metrics.density).toInt()
        val width = if (metrics.widthPixels > maxDialogWidth) maxDialogWidth else (metrics.widthPixels * 0.92f).toInt()
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawable(ColorDrawable(0))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val layoutId = res.getIdentifier("fragment_netnaija_settings", "layout", "com.netnaija")
        val layoutParser = res.getLayout(layoutId)
        val view = inflater.inflate(layoutParser, container, false)
        val dialogBackgroundId = res.getIdentifier("dialog_background", "drawable", "com.netnaija")
        if (dialogBackgroundId != 0) {
            view.setBackground(res.getDrawable(dialogBackgroundId, null))
        }

        val hostRow = findView(view, "hostRow")
        val hostSubtitle = findView(view, "hostSubtitle") as TextView
        val hostIcon = findView(view, "hostIcon") as ImageView
        val chevronHost = findView(view, "chevron_host") as ImageView
        val saveContainer = findView(view, "saveContainer")
        val saveIcon = findView(view, "saveIcon") as ImageView

        val settingsIconId = res.getIdentifier("settings_icon", "drawable", "com.netnaija")
        val saveIconId = res.getIdentifier("save_icon", "drawable", "com.netnaija")
        val chevronId = res.getIdentifier("ic_chevron", "drawable", "com.netnaija")
        val iconBgRedId = res.getIdentifier("ic_icon_bg_red", "drawable", "com.netnaija")
        val itemBgId = res.getIdentifier("settings_item_background", "drawable", "com.netnaija")
        val outlineId = res.getIdentifier("outline", "drawable", "com.netnaija")
        hostIcon.setImageDrawable(res.getDrawable(settingsIconId, null))
        saveIcon.setImageDrawable(res.getDrawable(saveIconId, null))
        chevronHost.setImageDrawable(res.getDrawable(chevronId, null))
        findView(view, "host_container").setBackground(res.getDrawable(iconBgRedId, null))
        hostRow.setBackground(res.getDrawable(itemBgId, null))
        saveContainer.setBackground(res.getDrawable(outlineId, null))

        val hostNames = HOST_POOL.map { it.removePrefix("https://") }.toTypedArray()
        var currentHostIndex = max(HOST_POOL.indexOf(sharedPref.getString("netnaija_host", HOST_POOL[4])), 0)
        hostSubtitle.text = "Current: ${hostNames[currentHostIndex]}"

        hostRow.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Select API Host")
                .setSingleChoiceItems(hostNames, currentHostIndex) { dialog, which ->
                    currentHostIndex = which
                    val selected = HOST_POOL[which]
                    sharedPref.edit().putString("netnaija_host", selected).apply()
                    hostSubtitle.text = "Current: ${hostNames[which]}"
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        saveContainer.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { dialog, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No", null)
                .show()
        }

        return view
    }

    // A full restart is the only way to make the freshly stored host reach the
    // already constructed provider instance.
    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            if (componentName != null) {
                val restartIntent = Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            }
        } catch (e: Exception) {
        }
    }
}
