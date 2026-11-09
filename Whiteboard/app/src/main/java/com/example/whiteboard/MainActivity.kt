package com.example.whiteboard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.whiteboard.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Survives rotation; holds only strokes and tool settings (no Views / Context).
    private val viewModel: DrawingViewModel by viewModels()
    private val state get() = viewModel.state

    private lateinit var swatches: List<Pair<View, Int>>
    private var clearDialog: AlertDialog? = null

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) saveDrawing() else toast(R.string.permission_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        swatches = listOf(
            binding.swatchBlack to color(R.color.pen_black),
            binding.swatchRed to color(R.color.pen_red),
            binding.swatchBlue to color(R.color.pen_blue),
            binding.swatchGreen to color(R.color.pen_green)
        )

        with(binding.drawingView) {
            attachState(state)
            onHistoryChanged = ::refreshHistoryButtons
        }

        setupToolbar()
        refreshToolSelection()
        refreshHistoryButtons()
    }

    private fun setupToolbar() {
        swatches.forEach { (view, penColor) ->
            view.setOnClickListener {
                state.penColor = penColor
                state.isEraser = false
                refreshToolSelection()
            }
        }

        binding.btnEraser.setOnClickListener {
            state.isEraser = !state.isEraser
            refreshToolSelection()
        }

        binding.btnThin.setOnClickListener { setBrushSize(DrawingState.SIZE_THIN) }
        binding.btnMedium.setOnClickListener { setBrushSize(DrawingState.SIZE_MEDIUM) }
        binding.btnThick.setOnClickListener { setBrushSize(DrawingState.SIZE_THICK) }

        binding.btnUndo.setOnClickListener { binding.drawingView.undo() }
        binding.btnRedo.setOnClickListener { binding.drawingView.redo() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnSave.setOnClickListener { saveDrawing() }
    }

    private fun setBrushSize(sizeDp: Float) {
        state.brushSizeDp = sizeDp
        refreshToolSelection()
    }

    private fun refreshToolSelection() {
        swatches.forEach { (view, penColor) ->
            view.isSelected = !state.isEraser && state.penColor == penColor
        }
        binding.btnEraser.isSelected = state.isEraser
        binding.btnThin.isSelected = state.brushSizeDp == DrawingState.SIZE_THIN
        binding.btnMedium.isSelected = state.brushSizeDp == DrawingState.SIZE_MEDIUM
        binding.btnThick.isSelected = state.brushSizeDp == DrawingState.SIZE_THICK
    }

    private fun refreshHistoryButtons() {
        setEnabled(binding.btnUndo, binding.drawingView.canUndo)
        setEnabled(binding.btnRedo, binding.drawingView.canRedo)
        setEnabled(binding.btnClear, !binding.drawingView.isEmpty)
    }

    private fun setEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    private fun confirmClear() {
        clearDialog?.dismiss()
        clearDialog = AlertDialog.Builder(this)
            .setTitle(R.string.clear_title)
            .setMessage(R.string.clear_message)
            .setPositiveButton(R.string.clear_confirm) { _, _ -> binding.drawingView.clearCanvas() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveDrawing() {
        if (binding.drawingView.isEmpty) {
            toast(R.string.nothing_to_save)
            return
        }
        // Only API 28 and below need a runtime permission; API 29+ uses scoped MediaStore.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val bitmap = binding.drawingView.createExportBitmap() ?: return
        val appContext = applicationContext
        binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ImageExporter.saveToGallery(appContext, bitmap) }
                }
                binding.btnSave.isEnabled = true
                toast(if (result.isSuccess) R.string.saved else R.string.save_failed)
            } finally {
                bitmap.recycle() // our private copy, never drawn on screen
            }
        }
    }

    override fun onDestroy() {
        // Avoid a leaked-window warning if the dialog is open during rotation.
        clearDialog?.dismiss()
        clearDialog = null
        super.onDestroy()
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun toast(@StringRes resId: Int) =
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
