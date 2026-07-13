package com.example.desktoppet

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.desktoppet.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val checkBoxes = mutableMapOf<String, CheckBox>()

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshState()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildPetList()

        binding.grantOverlayButton.setOnClickListener { requestOverlayPermission() }
        binding.applyButton.setOnClickListener { applySelection() }
        binding.removeAllButton.setOnClickListener { removeAll() }
        binding.wallpaperButton.setOnClickListener { openLiveWallpaperPicker() }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    /** Creates one checkbox per sprite sheet found in assets, restoring saved selection. */
    private fun buildPetList() {
        val selected = PetPrefs.selected(this)
        binding.petContainer.removeAllViews()
        checkBoxes.clear()
        for (pet in PetCatalog.available(this)) {
            val cb = CheckBox(this).apply {
                text = pet.displayName
                isChecked = pet.id in selected
                textSize = 18f
                setPadding(paddingLeft, dp(10), paddingRight, dp(10))
            }
            checkBoxes[pet.id] = cb
            binding.petContainer.addView(cb)
        }
        if (checkBoxes.isEmpty()) {
            binding.statusText.text = getString(R.string.status_no_pets)
        }
    }

    private fun selectedIds(): List<String> =
        checkBoxes.filterValues { it.isChecked }.keys.toList()

    private fun applySelection() {
        if (!canDrawOverlays()) {
            requestOverlayPermission()
            return
        }
        requestNotificationPermissionIfNeeded()

        val ids = selectedIds()
        PetPrefs.setSelected(this, ids.toSet()) // the live wallpaper reads this too

        if (ids.isEmpty()) {
            PetOverlayService.stop(this)
        } else {
            PetOverlayService.setPets(this, ids)
            moveTaskToBack(true) // step aside so the pets are visible over the home screen
        }
    }

    private fun removeAll() {
        checkBoxes.values.forEach { it.isChecked = false }
        PetPrefs.setSelected(this, emptySet())
        PetOverlayService.stop(this)
    }

    /** Opens the system live-wallpaper preview for our wallpaper (or the chooser as fallback). */
    private fun openLiveWallpaperPicker() {
        PetPrefs.setSelected(this, selectedIds().toSet()) // ensure the wallpaper shows current picks
        val component = ComponentName(this, PetWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        when {
            direct.resolveActivity(packageManager) != null -> startActivity(direct)
            chooser.resolveActivity(packageManager) != null -> startActivity(chooser)
            else -> Toast.makeText(this, R.string.wallpaper_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (canDrawOverlays()) {
            refreshState()
            return
        }
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshState() {
        val granted = canDrawOverlays()
        binding.grantOverlayButton.isEnabled = !granted
        binding.applyButton.isEnabled = granted
        if (checkBoxes.isNotEmpty()) {
            binding.statusText.text = if (granted) {
                getString(R.string.status_ready)
            } else {
                getString(R.string.status_need_permission)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
