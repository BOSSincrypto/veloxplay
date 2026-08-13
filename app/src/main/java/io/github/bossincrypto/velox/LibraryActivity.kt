package io.github.bossincrypto.velox

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.bossincrypto.velox.databinding.ActivityLibraryBinding

/** Device video list. Opens straight into [PlayerActivity]; holds no player itself. */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private val adapter = VideoAdapter(::open)

    private val permission =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refresh() else showPermissionPrompt()
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Not every provider grants a persistable permission; playback works either
                // way, only the saved resume position would be unusable next launch.
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                open(uri, null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.inflateMenu(R.menu.library)
        binding.toolbar.setOnMenuItemClickListener(::onMenuClick)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(true)
        // Rows are a fixed height, so the extra offscreen pass buys smoother flings for free.
        binding.list.setItemViewCacheSize(12)

        binding.grantButton.setOnClickListener { requestPermission.launch(permission) }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermission()) refresh() else showPermissionPrompt()
    }

    private fun onMenuClick(item: android.view.MenuItem): Boolean = when (item.itemId) {
        R.id.action_open_file -> {
            pickFile.launch(arrayOf("video/*", "application/x-mpegURL", "application/dash+xml"))
            true
        }
        R.id.action_open_url -> {
            askForUrl()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> false
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun showPermissionPrompt() {
        binding.permissionGroup.isVisible = true
        binding.list.isVisible = false
        binding.empty.isVisible = false
    }

    private fun refresh() {
        binding.permissionGroup.isVisible = false
        binding.progress.isVisible = true
        val ctx = applicationContext
        VideoLibrary.submit {
            val items = VideoLibrary.query(ctx)
            binding.list.post {
                binding.progress.isVisible = false
                binding.list.isVisible = items.isNotEmpty()
                binding.empty.isVisible = items.isEmpty()
                adapter.submitList(items)
            }
        }
    }

    private fun askForUrl() {
        val input = EditText(this).apply {
            hint = getString(R.string.url_hint)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_url)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.play) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) open(Uri.parse(text), text)
            }
            .show()
    }

    private fun open(item: VideoItem) = open(item.uri, item.title)

    private fun open(uri: Uri, title: String?) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setData(uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, title)
        )
    }
}
