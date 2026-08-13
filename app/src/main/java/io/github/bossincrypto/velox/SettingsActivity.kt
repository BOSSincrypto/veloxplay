package io.github.bossincrypto.velox

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.github.bossincrypto.velox.databinding.ActivitySettingsBinding

/**
 * Global settings. Speed set here applies to every video the app plays, now and later.
 *
 * ponytail: hand-written switches instead of androidx.preference - seven settings do not
 * justify a preference framework, its XML inflation cost, or another dependency.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        bindSpeed()
        bindHoldSpeed()
        bindSeekStep()

        binding.switchFastSeek.isChecked = Prefs.fastSeek
        binding.switchFastSeek.setOnCheckedChangeListener { _, checked ->
            Prefs.fastSeek = checked
            PlayerHolder.peek()?.let { PlayerHolder.applySeekParameters(it) }
        }

        binding.switchResume.isChecked = Prefs.resumePlayback
        binding.switchResume.setOnCheckedChangeListener { _, checked -> Prefs.resumePlayback = checked }

        binding.switchGestures.isChecked = Prefs.gesturesEnabled
        binding.switchGestures.setOnCheckedChangeListener { _, checked -> Prefs.gesturesEnabled = checked }

        binding.switchBackground.isChecked = Prefs.backgroundPlayback
        binding.switchBackground.setOnCheckedChangeListener { _, checked -> Prefs.backgroundPlayback = checked }
    }

    private fun bindSpeed() {
        binding.speedSlider.valueFrom = Prefs.MIN_SPEED
        binding.speedSlider.valueTo = Prefs.MAX_SPEED
        binding.speedSlider.stepSize = 0.05f
        binding.speedSlider.value = Prefs.speed
        binding.speedValue.text = Format.speed(Prefs.speed)
        binding.speedSlider.addOnChangeListener { _, value, _ ->
            Prefs.speed = value
            binding.speedValue.text = Format.speed(value)
            // Live-apply so a video playing in the background changes immediately.
            PlayerHolder.peek()?.setPlaybackSpeed(value)
        }
        binding.speedReset.setOnClickListener { binding.speedSlider.value = 1.0f }
    }

    private fun bindHoldSpeed() {
        binding.holdSlider.valueFrom = 1.0f
        binding.holdSlider.valueTo = Prefs.MAX_SPEED
        binding.holdSlider.stepSize = 0.25f
        binding.holdSlider.value = Prefs.holdSpeed
        binding.holdValue.text = Format.speed(Prefs.holdSpeed)
        binding.holdSlider.addOnChangeListener { _, value, _ ->
            Prefs.holdSpeed = value
            binding.holdValue.text = Format.speed(value)
        }
    }

    private fun bindSeekStep() {
        binding.stepSlider.valueFrom = 5f
        binding.stepSlider.valueTo = 60f
        binding.stepSlider.stepSize = 5f
        binding.stepSlider.value = (Prefs.seekStepMs / 1000).toFloat()
        binding.stepValue.text = getString(R.string.seconds_value, Prefs.seekStepMs / 1000)
        binding.stepSlider.addOnChangeListener { _, value, _ ->
            Prefs.seekStepMs = (value * 1000).toInt()
            binding.stepValue.text = getString(R.string.seconds_value, value.toInt())
        }
    }
}
