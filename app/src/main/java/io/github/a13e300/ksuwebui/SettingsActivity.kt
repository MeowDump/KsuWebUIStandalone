package io.github.a13e300.ksuwebui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import io.github.a13e300.ksuwebui.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupSystemInfo()
        setupPowerMenu()
        setupThemeToggle()
        setupBannerToggle()
        setupCardButtonsToggle()
        setupCopyAllModules()
        setupDonate()
        setupFooter()
    }

    private fun setupSystemInfo() {
        binding.tvAndroidVersion.text = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.tvRootProvider.text = detectRootProvider()
        binding.tvKernelVersion.text = getKernelVersion()

        binding.tvRootProvider.setOnLongClickListener {
            copyToClipboard("Root Provider", binding.tvRootProvider.text.toString())
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun detectRootProvider(): String {
        return when {
            Shell.cmd("[ -d /data/adb/ksu ]").exec().isSuccess -> "KernelSU"
            Shell.cmd("[ -f /data/adb/ap/package_config ]").exec().isSuccess -> "APatch"
            Shell.cmd("[ -f /data/adb/magisk/util_functions.sh ]").exec().isSuccess -> "Magisk"
            Shell.cmd("which su").exec().isSuccess -> "Root (Unknown)"
            else -> "No Root"
        }
    }

    private fun getKernelVersion(): String {
        return try {
            System.getProperty("os.version") ?: "Unknown"
        } catch (_: Exception) { "Unknown" }
    }

    private fun setupPowerMenu() {
        binding.btnPowerMenu.setOnClickListener {
            val options = arrayOf(
                "Reboot",
                "Restart SystemUI",
                "Shutdown",
                "Reboot to Recovery",
                "Reboot to Bootloader",
                "Reboot to Safe Mode"
            )
            MaterialAlertDialogBuilder(this)
                .setTitle("Power Menu")
                .setItems(options) { _, which ->
                    val cmd = when (which) {
                        0 -> "reboot"
                        1 -> "killall system_server"
                        2 -> "reboot -p"
                        3 -> "reboot recovery"
                        4 -> "reboot bootloader"
                        5 -> "setprop persist.sys.safemode 1 && reboot"
                        else -> return@setItems
                    }
                    App.executor.submit {
                        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                            it.newJob().add(cmd).exec()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupThemeToggle() {
        val currentTheme = prefs.getString("theme", "system")
        val checkId = when (currentTheme) {
            "light" -> R.id.btnThemeLight
            "amoled" -> R.id.btnThemeAmoled
            else -> R.id.btnThemeSystem
        }
        binding.themeGroup.check(checkId)

        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val theme = when (checkedId) {
                R.id.btnThemeLight -> "light"
                R.id.btnThemeAmoled -> "amoled"
                else -> "system"
            }
            prefs.edit(commit = true) { putString("theme", theme) }
            restartApp()
        }
    }

    private fun setupBannerToggle() {
        val show = prefs.getBoolean("show_banners", true)
        binding.switchBanners.isChecked = show
        binding.switchBanners.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit(commit = true) { putBoolean("show_banners", isChecked) }
            restartApp()
        }
    }

    private fun setupCardButtonsToggle() {
        val show = prefs.getBoolean("show_card_buttons", true)
        binding.switchCardButtons.isChecked = show
        binding.switchCardButtons.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit(commit = true) { putBoolean("show_card_buttons", isChecked) }
            restartApp()
        }
    }

    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        finish()
        Process.killProcess(Process.myPid())
    }

    private fun setupCopyAllModules() {
        binding.btnCopyAllModules.setOnClickListener {
            copyAllModuleDetails()
        }
    }

    private fun copyAllModuleDetails() {
        App.executor.submit {
            val result = Shell.cmd("ls /data/adb/modules/").exec()
            val moduleIds = result.out.filter { it.isNotBlank() }
            val sb = StringBuilder()
            sb.appendLine("=== All Modules ===")
            sb.appendLine()

            moduleIds.forEach { moduleId ->
                val propResult = Shell.cmd("cat /data/adb/modules/$moduleId/module.prop 2>/dev/null").exec()
                if (propResult.out.isNotEmpty()) {
                    sb.appendLine("--- $moduleId ---")
                    propResult.out.forEach { sb.appendLine(it) }
                    val disabled = Shell.cmd("[ -f /data/adb/modules/$moduleId/disable ] && echo 1 || echo 0").exec()
                    val removed = Shell.cmd("[ -f /data/adb/modules/$moduleId/remove ] && echo 1 || echo 0").exec()
                    sb.appendLine("disabled=${disabled.out.firstOrNull() ?: "0"}")
                    sb.appendLine("remove=${removed.out.firstOrNull() ?: "0"}")
                    sb.appendLine()
                }
            }

            runOnUiThread {
                copyToClipboard("All Module Details", sb.toString())
                Toast.makeText(this, "All module details copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupDonate() {
        binding.btnDonate.setOnClickListener {
            openUrl("https://meowdump.github.io")
        }
    }

    private fun setupFooter() {
        binding.tvAppVersion.text = "MADE WITH LOVE BY MEOW"
        binding.tvAppVersion.textSize = 14f
        binding.tvAppVersion.setTextColor(Color.parseColor("#FFBB86FC"))
        binding.tvAppVersion.setOnClickListener {
            openUrl("https://t.me/MeowDump")
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: Exception) {
            Toast.makeText(this, "Cannot open URL", Toast.LENGTH_SHORT).show()
        }
    }
}
