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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.databinding.ActivityMainBinding
import io.github.a13e300.ksuwebui.databinding.ItemModuleBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
            val cutoutAndBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = cutoutAndBars.left, top = cutoutAndBars.top, right = cutoutAndBars.right)
            return@setOnApplyWindowInsetsListener insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { v, insets ->
            val cutoutAndBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = cutoutAndBars.left, bottom = cutoutAndBars.bottom, right = cutoutAndBars.right)
            return@setOnApplyWindowInsetsListener insets
        }

        adapter = Adapter()
        binding.list.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener {
            refresh()
        }
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
        menu.findItem(R.id.enable_monet).apply {
            isChecked = prefs.getBoolean("enable_monet", true)
            setOnMenuItemClickListener {
                val newValue = !it.isChecked
                prefs.edit { putBoolean("enable_monet", newValue) }
                it.isChecked = newValue
                true
            }
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
            fs.getFile("/data/adb/modules").listFiles()!!.forEach { f ->
                if (!f.isDirectory) return@forEach
                if (!fs.getFile(f, "webroot").isDirectory) return@forEach
                if (!fs.getFile(f, "module.prop").exists()) return@forEach
                if (fs.getFile(f, "disable").exists() && !showDisabled) return@forEach
                var name = f.name
                val id = f.name
                var author = "?"
                var version = "?"
                var desc = ""
                val hasAction = fs.getFile(f, "action.sh").exists()
                val isDisabled = fs.getFile(f, "disable").exists()
                val isRemoved = fs.getFile(f, "remove").exists()
                fs.getFile(f, "module.prop").newInputStream().bufferedReader().use {
                    it.lines().forEach { l ->
                        val ls = l.split("=", limit = 2)
                        if (ls.size == 2) {
                            if (ls[0] == "name") name = ls[1]
                            else if (ls[0] == "description") desc = ls[1]
                            else if (ls[0] == "author") author = ls[1]
                            else if (ls[0] == "version") version = ls[1]
                        }
                    }
                }
                mods.add(Module(name, id, desc, author, version, hasAction, isDisabled, isRemoved))
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
        val hasAction: Boolean = false,
        var isDisabled: Boolean = false,
        var isRemoved: Boolean = false,
        var pinned: Boolean = false
    )

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    inner class Adapter : RecyclerView.Adapter<ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                ItemModuleBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun getItemCount(): Int = moduleList.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = moduleList[position]
            val id = item.id
            val name = item.name
            holder.binding.name.text = name
            holder.binding.author.text = resources.getString(R.string.author, item.author)
            holder.binding.version.text = resources.getString(R.string.version, item.version)
            holder.binding.desc.text = item.desc
            holder.binding.name.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, if (item.pinned) R.drawable.ic_push_pin else 0, 0
            )

            holder.binding.toggle.isChecked = !item.isDisabled
            holder.binding.toggle.setOnCheckedChangeListener(null)
            holder.binding.toggle.setOnCheckedChangeListener { _, isChecked ->
                val modulePath = "/data/adb/modules/$id"
                val disableFile = "$modulePath/disable"
                App.executor.submit {
                    if (isChecked) {
                        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                            it.newJob().add("rm -f " + disableFile).exec()
                        }
                    } else {
                        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                            it.newJob().add("touch " + disableFile).exec()
                        }
                    }
                    runOnUiThread {
                        item.isDisabled = !isChecked
                        val showDisabled = prefs.getBoolean("show_disabled", false)
                        if (!showDisabled && !isChecked) {
                            moduleList = moduleList.filter { it.id != id }
                            notifyDataSetChanged()
                        }
                    }
                }
            }

            holder.binding.actionButton.visibility = if (item.hasAction) View.VISIBLE else View.GONE
            holder.binding.actionButton.setOnClickListener {
                showLoadingOverlay(true)
                val scriptPath = "/data/adb/modules/$id/action.sh"
                App.executor.submit {
                    val result = Shell.cmd("sh $scriptPath").exec()
                    val output = if (result.out.isNotEmpty()) {
                        result.out.joinToString("\n")
                    } else {
                        "[No output]"
                    }
                    runOnUiThread {
                        showLoadingOverlay(false)
                        showActionOutputDialog(name, output)
                    }
                }
            }

            holder.binding.removeButton.text = if (item.isRemoved) "Restore" else "Remove"
            holder.binding.removeButton.setTextColor(
                if (item.isRemoved) {
                    Color.parseColor("#2E7D32")
                } else {
                    ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
                }
            )
            holder.binding.removeButton.setOnClickListener {
                val modulePath = "/data/adb/modules/$id"
                val removeFile = "$modulePath/remove"
                App.executor.submit {
                    if (item.isRemoved) {
                        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                            it.newJob().add("rm -f " + removeFile).exec()
                        }
                    } else {
                        Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).build().use {
                            it.newJob().add("touch " + removeFile).exec()
                        }
                    }
                    runOnUiThread {
                        item.isRemoved = !item.isRemoved
                        holder.binding.removeButton.text = if (item.isRemoved) "Restore" else "Remove"
                        holder.binding.removeButton.setTextColor(
                            if (item.isRemoved) {
                                Color.parseColor("#2E7D32")
                            } else {
                                ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
                            }
                        )
                    }
                }
            }

            holder.binding.root.setOnClickListener {
                shouldRefresh = true
                startActivity(
                    Intent(this@MainActivity, WebUIActivity::class.java)
                        .setData("ksuwebui://webui/$id".toUri())
                        .putExtra("id", id)
                        .putExtra("name", name)
                )
            }
            holder.binding.root.setOnLongClickListener {
                item.pinned = !item.pinned
                val pinnedIds = getPinnedModules()
                if (item.pinned) {
                    pinnedIds.add(item.id)
                } else {
                    pinnedIds.remove(item.id)
                }
                savePinnedModules(pinnedIds)
                moduleList = moduleList.sortedWith(compareByDescending<Module> { it.pinned }.thenBy { it.name.lowercase() })
                notifyDataSetChanged()
                true
            }
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
            .setTitle(moduleName + " ▶︎ console log")
            .setView(container)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Script Output", output))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileSystemService.removeListener(this)
    }
}
