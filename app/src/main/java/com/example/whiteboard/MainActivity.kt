package com.example.whiteboard

import android.Manifest
import android.content.pm.PackageManager
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
        refreshSlideControls()
    }

    private fun setupToolbar() {
        swatches.forEach { (view, penColor) ->
            view.setOnClickListener {
                state.penColor = penColor
                state.isEraser = false
                binding.drawingView.setTool(DrawingTool.PEN)
                refreshToolSelection()
            }
        }

        binding.btnEraser.setOnClickListener {
            state.isEraser = !state.isEraser
            binding.drawingView.setTool(DrawingTool.PEN)
            refreshToolSelection()
        }

        binding.btnThin.setOnClickListener { setBrushSize(DrawingState.SIZE_THIN) }
        binding.btnMedium.setOnClickListener { setBrushSize(DrawingState.SIZE_MEDIUM) }
        binding.btnThick.setOnClickListener { setBrushSize(DrawingState.SIZE_THICK) }

        binding.btnUndo.setOnClickListener { binding.drawingView.undo() }
        binding.btnRedo.setOnClickListener { binding.drawingView.redo() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnSave.setOnClickListener { saveDrawing() }
        binding.btnSelect.setOnClickListener { chooseRegionTool(DrawingTool.SELECT) }
        binding.btnAreaErase.setOnClickListener { chooseRegionTool(DrawingTool.AREA_ERASER) }
        binding.btnShape.setOnClickListener { chooseShape() }
        binding.btnFill.setOnClickListener {
            state.fillShapes = !state.fillShapes
            refreshToolSelection()
        }
        binding.btnPrevSlide.setOnClickListener { showSlide(viewModel.currentSlide - 1) }
        binding.btnNextSlide.setOnClickListener { showSlide(viewModel.currentSlide + 1) }
        binding.btnAddSlide.setOnClickListener {
            binding.drawingView.attachState(viewModel.addSlide())
            refreshSlideControls()
            refreshToolSelection()
        }
    }

    private fun setBrushSize(sizeDp: Float) {
        state.brushSizeDp = sizeDp
        binding.drawingView.setTool(DrawingTool.PEN)
        refreshToolSelection()
    }

    private fun chooseShape() {
        val labels = arrayOf(
            getString(R.string.shape_rectangle),
            getString(R.string.shape_rounded_rectangle),
            getString(R.string.shape_oval),
            getString(R.string.shape_triangle),
            getString(R.string.shape_diamond),
            getString(R.string.shape_pentagon),
            getString(R.string.shape_hexagon),
            getString(R.string.shape_star),
            getString(R.string.shape_arrow),
            getString(R.string.shape_line)
        )
        val shapes = arrayOf(
            ShapeType.RECTANGLE,
            ShapeType.ROUNDED_RECTANGLE,
            ShapeType.OVAL,
            ShapeType.TRIANGLE,
            ShapeType.DIAMOND,
            ShapeType.PENTAGON,
            ShapeType.HEXAGON,
            ShapeType.STAR,
            ShapeType.ARROW,
            ShapeType.LINE
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_shape)
            .setItems(labels) { _, index ->
                binding.drawingView.setShapeType(shapes[index])
                state.isEraser = false
                refreshToolSelection()
            }
            .show()
    }

    private fun chooseRegionTool(tool: DrawingTool) {
        val labels = arrayOf(
            getString(R.string.region_rectangle),
            getString(R.string.region_oval),
            getString(R.string.region_lasso)
        )
        val regions = arrayOf(RegionType.RECTANGLE, RegionType.OVAL, RegionType.LASSO)
        AlertDialog.Builder(this)
            .setTitle(if (tool == DrawingTool.SELECT) R.string.select_area else R.string.erase_area)
            .setItems(labels) { _, index ->
                binding.drawingView.setRegionType(regions[index])
                binding.drawingView.setTool(tool)
                state.isEraser = false
                refreshToolSelection()
                if (tool == DrawingTool.SELECT) toast(R.string.select_help)
            }
            .show()
    }

    private fun showSlide(index: Int) {
        if (index !in viewModel.slides.indices) return
        binding.drawingView.attachState(viewModel.changeSlide(index))
        refreshSlideControls()
        refreshToolSelection()
    }

    private fun refreshSlideControls() {
        binding.tvSlide.text = getString(
            R.string.slide_counter,
            viewModel.currentSlide + 1,
            viewModel.slides.size
        )
        binding.btnPrevSlide.isEnabled = viewModel.currentSlide > 0
        binding.btnNextSlide.isEnabled = viewModel.currentSlide < viewModel.slides.lastIndex
    }

    private fun refreshToolSelection() {
        swatches.forEach { (view, penColor) ->
            view.isSelected = !state.isEraser && state.penColor == penColor
        }
        binding.btnEraser.isSelected = state.isEraser
        binding.btnThin.isSelected = state.brushSizeDp == DrawingState.SIZE_THIN
        binding.btnMedium.isSelected = state.brushSizeDp == DrawingState.SIZE_MEDIUM
        binding.btnThick.isSelected = state.brushSizeDp == DrawingState.SIZE_THICK
        binding.btnFill.isSelected = state.fillShapes
        binding.btnSelect.isSelected = binding.drawingView.tool == DrawingTool.SELECT
        binding.btnAreaErase.isSelected = binding.drawingView.tool == DrawingTool.AREA_ERASER
        binding.btnShape.isSelected = binding.drawingView.tool == DrawingTool.SHAPE
        binding.btnShape.text = getString(R.string.tool_shape)
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
