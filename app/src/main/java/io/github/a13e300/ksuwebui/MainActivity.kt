package io.github.a13e300.ksuwebui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.databinding.ActivityMainBinding
import io.github.a13e300.ksuwebui.databinding.ItemModuleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Properties
import kotlin.random.Random

@SuppressLint("NotifyDataSetChanged")
class MainActivity : AppCompatActivity(), FileSystemService.Listener {
    private lateinit var binding: ActivityMainBinding
    private var moduleList = emptyList<Module>()
    private lateinit var adapter: Adapter
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var shouldRefresh = false

    private fun getPinnedModules(): MutableSet<String> {
        return prefs.getStringSet("pinned_modules", emptySet<String>())?.toMutableSet() ?: mutableSetOf()
    }

    private fun savePinnedModules(pinned: Set<String>) {
        prefs.edit { putStringSet("pinned_modules", pinned) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        lifecycleScope.launch(Dispatchers.IO) {
            AppList.getApps(this@MainActivity)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.appbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, bottom = bars.bottom, right = bars.right)
            insets
        }

        adapter = Adapter()
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { refresh() }
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.enable_webview_debugging).apply {
            isChecked = prefs.getBoolean("enable_web_debugging", BuildConfig.DEBUG)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit { putBoolean("enable_web_debugging", newValue) }
                it.isChecked = newValue
                true
            }
        }
        menu.findItem(R.id.show_disabled).apply {
            isChecked = prefs.getBoolean("show_disabled", false)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit { putBoolean("show_disabled", newValue) }
                it.isChecked = newValue
                refresh()
                true
            }
        }
        menu.findItem(R.id.settings).setOnMenuItemClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        if (shouldRefresh) {
            refresh()
            shouldRefresh = false
        }
    }

    private fun refresh() {
        binding.swipeRefresh.isRefreshing = true
        moduleList = emptyList()
        adapter.notifyDataSetChanged()
        binding.info.setText(R.string.loading)
        binding.info.isVisible = true
        FileSystemService.start(this)
    }

    override fun onServiceAvailable(fs: FileSystemManager) {
        App.executor.submit {
            val mods = mutableListOf<Module>()
            val showDisabled = prefs.getBoolean("show_disabled", false)
            val modulesDir = fs.getFile("/data/adb/modules")
            val files = modulesDir.listFiles() ?: emptyArray()

            files.forEach { f ->
                if (!f.isDirectory) return@forEach
                if (!fs.getFile(f, "module.prop").exists()) return@forEach
                if (fs.getFile(f, "disable").exists() && !showDisabled) return@forEach

                val id = f.name
                val propFile = fs.getFile(f, "module.prop")
                val props = Properties()
                propFile.newInputStream().bufferedReader().use { props.load(it) }

                val name = props.getProperty("name", id)
                val desc = props.getProperty("description", "")
                val author = props.getProperty("author", "?")
                val version = props.getProperty("version", "?")
                val versionCode = props.getProperty("versionCode", "0")
                val banner = props.getProperty("banner", "")
                val support = props.getProperty("support", "")
                val donate = props.getProperty("donate", "")
                val updateJson = props.getProperty("updateJson", "")

                val hasWebroot = fs.getFile(f, "webroot").isDirectory
                val hasAction = fs.getFile(f, "action.sh").exists()
                val isDisabled = fs.getFile(f, "disable").exists()
                val isRemoved = fs.getFile(f, "remove").exists()

                mods.add(Module(
                    name = name, id = id, desc = desc, author = author,
                    version = version, versionCode = versionCode,
                    banner = banner, support = support, donate = donate,
                    updateJson = updateJson,
                    hasWebroot = hasWebroot, hasAction = hasAction,
                    isDisabled = isDisabled, isRemoved = isRemoved
                ))
            }

            val pinnedIds = getPinnedModules()
            mods.forEach { it.pinned = it.id in pinnedIds }
            mods.sortWith(compareByDescending<Module> { it.pinned }.thenBy { it.name.lowercase() })

            runOnUiThread {
                moduleList = mods
                adapter.notifyDataSetChanged()
                binding.swipeRefresh.isRefreshing = false
                if (mods.isEmpty()) {
                    binding.info.setText(R.string.no_modules)
                    binding.info.isVisible = true
                } else {
                    binding.info.isVisible = false
                }
            }
        }
    }

    override fun onLaunchFailed() {
        moduleList = emptyList()
        adapter.notifyDataSetChanged()
        binding.info.setText(R.string.please_grant_root)
        binding.info.isVisible = true
        binding.swipeRefresh.isRefreshing = false
    }

    data class Module(
        val name: String,
        val id: String,
        val desc: String,
        val author: String,
        val version: String,
        val versionCode: String = "0",
        val banner: String = "",
        val support: String = "",
        val donate: String = "",
        val updateJson: String = "",
        val hasWebroot: Boolean = false,
        val hasAction: Boolean = false,
        var isDisabled: Boolean = false,
        var isRemoved: Boolean = false,
        var pinned: Boolean = false
    ) {
        fun getBannerUrl(moduleDir: String): String? = when {
            banner.isBlank() -> null
            banner.startsWith("http://") || banner.startsWith("https://") -> banner
            else -> "file://$moduleDir/webroot/$banner"
        }

        fun getFormattedDetails(moduleDir: String): String {
            return buildString {
                appendLine("Module ID: $id")
                appendLine("Name: $name")
                appendLine("Version: $version")
                appendLine("Version Code: $versionCode")
                appendLine("Author: $author")
                appendLine("Description: $desc")
                if (updateJson.isNotBlank()) appendLine("Update JSON: $updateJson")
                appendLine("Path: $moduleDir")
                appendLine("WebUI: ${if (hasWebroot) "Yes" else "No"}")
                appendLine("Action: ${if (hasAction) "Yes" else "No"}")
                appendLine("Status: ${if (isDisabled) "Disabled" else "Enabled"}${if (isRemoved) " | Marked for removal" else ""}")
            }
        }
    }

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    inner class Adapter : RecyclerView.Adapter<ViewHolder>() {
        private val showCardButtons = prefs.getBoolean("show_card_buttons", true)
        private val showBanners = prefs.getBoolean("show_banners", true)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemModuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount(): Int = moduleList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = moduleList[position]
            val id = item.id
            val name = item.name
            val moduleDir = "/data/adb/modules/$id"

            val bannerUrl = item.getBannerUrl(moduleDir)
            if (showBanners) {
                val urlToLoad = bannerUrl ?: getRandomFallbackBanner()
                holder.binding.bannerContainer.visibility = View.VISIBLE
                holder.binding.bannerOverlay.visibility = View.VISIBLE
                Glide.with(this@MainActivity)
                    .load(urlToLoad)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .placeholder(android.R.color.transparent)
                    .error(android.R.color.transparent)
                    .centerCrop()
                    .into(holder.binding.moduleBanner)
            } else {
                holder.binding.bannerContainer.visibility = View.GONE
                holder.binding.bannerOverlay.visibility = View.GONE
            }

            holder.binding.name.text = name
            holder.binding.author.text = getString(R.string.author, item.author)
            holder.binding.version.text = getString(R.string.version, item.version)
            holder.binding.desc.text = item.desc
            holder.binding.name.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, if (item.pinned) R.drawable.ic_push_pin else 0, 0
            )

            holder.binding.toggle.isChecked = !item.isDisabled
            holder.binding.toggle.setOnCheckedChangeListener(null)
            holder.binding.toggle.setOnCheckedChangeListener { _, isChecked ->
                val disableFile = "/data/adb/modules/$id/disable"
                App.executor.submit {
                    Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                        if (isChecked) it.newJob().add("rm -f $disableFile").exec()
                        else it.newJob().add("touch $disableFile").exec()
                    }
                    runOnUiThread {
                        item.isDisabled = !isChecked
                        if (!prefs.getBoolean("show_disabled", false) && !isChecked) {
                            moduleList = moduleList.filter { it.id != id }
                            notifyDataSetChanged()
                        }
                    }
                }
            }

            holder.binding.openButton.visibility = if (item.hasWebroot) View.VISIBLE else View.GONE
            holder.binding.openButton.setOnClickListener {
                shouldRefresh = true
                startActivity(
                    Intent(this@MainActivity, WebUIActivity::class.java)
                        .setData("ksuwebui://webui/$id".toUri())
                        .putExtra("id", id)
                        .putExtra("name", name)
                )
            }

            holder.binding.actionButton.visibility = if (item.hasAction) View.VISIBLE else View.GONE
            holder.binding.actionButton.setOnClickListener {
                showLoadingOverlay(true)
                val scriptPath = "/data/adb/modules/$id/action.sh"
                App.executor.submit {
                    val result = Shell.cmd("sh $scriptPath").exec()
                    val output = if (result.out.isNotEmpty()) {
                        result.out.joinToString(separator = "\n")
                    } else {
                        "[No output]"
                    }
                    runOnUiThread {
                        showLoadingOverlay(false)
                        showActionOutputDialog(name, output)
                    }
                }
            }

            if (showCardButtons) {
                holder.binding.secondaryActions.visibility = View.VISIBLE
                if (item.donate.isNotBlank()) {
                    holder.binding.donateButton.visibility = View.VISIBLE
                    holder.binding.donateButton.setOnClickListener { openUrl(item.donate) }
                } else {
                    holder.binding.donateButton.visibility = View.GONE
                }

                if (item.support.isNotBlank()) {
                    holder.binding.supportButton.visibility = View.VISIBLE
                    holder.binding.supportButton.setOnClickListener { openUrl(item.support) }
                } else {
                    holder.binding.supportButton.visibility = View.GONE
                }

                holder.binding.copyDetailsButton.setOnClickListener {
                    copyToClipboard("Module Details", item.getFormattedDetails(moduleDir))
                    Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            } else {
                holder.binding.secondaryActions.visibility = View.GONE
            }

            holder.binding.removeButton.text = if (item.isRemoved) "Restore" else "Remove"
            if (item.isRemoved) {
                holder.binding.removeButton.setTextColor(Color.WHITE)
                holder.binding.removeButton.setBackgroundColor(Color.parseColor("#2E7D32"))
            } else {
                holder.binding.removeButton.setTextColor(Color.WHITE)
                holder.binding.removeButton.setBackgroundColor(Color.parseColor("#B3261E"))
            }
            holder.binding.removeButton.setOnClickListener {
                val removeFile = "/data/adb/modules/$id/remove"
                App.executor.submit {
                    Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                        if (item.isRemoved) it.newJob().add("rm -f $removeFile").exec()
                        else it.newJob().add("touch $removeFile").exec()
                    }
                    runOnUiThread {
                        item.isRemoved = !item.isRemoved
                        holder.binding.removeButton.text = if (item.isRemoved) "Restore" else "Remove"
                        if (item.isRemoved) {
                            holder.binding.removeButton.setTextColor(Color.WHITE)
                            holder.binding.removeButton.setBackgroundColor(Color.parseColor("#2E7D32"))
                        } else {
                            holder.binding.removeButton.setTextColor(Color.WHITE)
                            holder.binding.removeButton.setBackgroundColor(Color.parseColor("#B3261E"))
                        }
                    }
                }
            }

            holder.binding.root.setOnLongClickListener {
                item.pinned = !item.pinned
                val pinnedIds = getPinnedModules()
                if (item.pinned) pinnedIds.add(item.id) else pinnedIds.remove(item.id)
                savePinnedModules(pinnedIds)
                moduleList = moduleList.sortedWith(compareByDescending<Module> { it.pinned }.thenBy { it.name.lowercase() })
                notifyDataSetChanged()
                true
            }
        }
    }

    private fun getRandomFallbackBanner(): String {
        val num = Random.nextInt(1, 15)
        return "https://raw.githubusercontent.com/MeowDump/MeowDump/refs/heads/main/Banner/mona$num.png"
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

    private fun showLoadingOverlay(show: Boolean) {
        binding.loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showActionOutputDialog(moduleName: String, output: String) {
        val textView = TextView(this).apply {
            text = output
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(48, 32, 48, 32)
        }
        val container = FrameLayout(this).apply {
            addView(textView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("$moduleName - Action Output")
            .setView(container)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                copyToClipboard("Action Output", output)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
