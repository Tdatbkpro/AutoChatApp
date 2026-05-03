package com.example.autochat.ui.phone

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import com.example.autochat.R
import com.example.autochat.databinding.DialogCodeZoomBinding
import com.example.autochat.databinding.ItemCodeBlockBinding
import com.example.autochat.databinding.ItemTableBinding
import io.noties.markwon.Markwon
import androidx.core.graphics.toColorInt
import androidx.core.view.children
import androidx.core.view.isEmpty
import com.example.autochat.CodeExecutor
import com.example.autochat.RunStateManager
import com.example.autochat.databinding.BottomSheetRunCodeBinding
import com.example.autochat.databinding.ItemMathBlockBinding
import com.example.autochat.domain.model.CodeResult
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MarkdownRenderer(private val markwon: Markwon, private val codeExecutor: CodeExecutor? = null) {

    private val CODE_BLOCK_REGEX = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    private val MATH_BLOCK_REGEX = Regex("\\\\\\[([\\s\\S]+?)\\\\\\]")  // \[...\]
    private val MATH_BLOCK_DOLLAR_REGEX = Regex("\\$\\$([\\s\\S]+?)\\$\\$")  // $$...$$

    // Thêm hàm này vào class MarkdownRenderer
    private fun preprocessLatex(content: String): String {
        val codeBlocks = mutableListOf<String>()
        var result = CODE_BLOCK_REGEX.replace(content) { match ->
            codeBlocks.add(match.value)
            "§CODE_BLOCK_${codeBlocks.size - 1}§"
        }

        // ✅ Normalize \(...\) → $...$ (phòng model trả về sai format)
        result = result.replace(Regex("\\\\\\((.+?)\\\\\\)")) { match ->
            "\$${match.groupValues[1]}\$"
        }

        // ✅ Normalize \[...\] → $$...$$
        result = result.replace(Regex("\\\\\\[([\\s\\S]+?)\\\\\\]")) { match ->
            "\$\$${match.groupValues[1]}\$\$"
        }

        // Bảo vệ $$ blocks
        val dollarBlocks = mutableListOf<String>()
        result = MATH_BLOCK_DOLLAR_REGEX.replace(result) { match ->
            dollarBlocks.add(match.value)
            "§DOLLAR_BLOCK_${dollarBlocks.size - 1}§"
        }

        // Convert inline $...$ → \(...\) để Markwon render
        result = result.replace(Regex("\\$([^\\$\\n]+?)\\$")) { match ->
            "\\(${match.groupValues[1]}\\)"
        }

        // Restore $$ blocks
        dollarBlocks.forEachIndexed { i, b -> result = result.replace("§DOLLAR_BLOCK_${i}§", b) }
        codeBlocks.forEachIndexed { i, b -> result = result.replace("§CODE_BLOCK_${i}§", b) }

        return result
    }
    fun render(container: LinearLayout, content: String) {
        container.removeAllViews()

        val clean = content.replace(
            Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), ""
        ).replace(Regex("<think>[\\s\\S]*", RegexOption.DOT_MATCHES_ALL), "").trim()
        val preprocessed = preprocessLatex(clean)
        android.util.Log.d("LATEX_DEBUG", "preprocessed:\n$preprocessed")

        val blocks = findBlocks(preprocessed).sortedBy { it.start }
        // ✅ Không có block nào → render thẳng toàn bộ, return luôn
        if (blocks.isEmpty()) {
            container.addView(makeTextView(container, preprocessed, markwon))
            return
        }

        var lastIndex = 0
        for (block in blocks) {
            if (block.start > lastIndex) {
                val textBefore = preprocessed.substring(lastIndex, block.start)
                if (textBefore.isNotBlank()) {
                    container.addView(makeTextView(container, textBefore, markwon))
                }
            }
            when (block) {
                is Block.Code -> container.addView(makeCodeBlock(container, block.language, block.code))
                is Block.Table -> container.addView(makeTableBlock(container, block.header, block.rows))
                is Block.Math -> container.addView(makeMathBlock(container, block.latex))
            }
            lastIndex = block.end
        }

        // Text còn lại sau block cuối
        if (lastIndex < preprocessed.length) {
            val remaining = preprocessed.substring(lastIndex)
            if (remaining.isNotBlank()) {
                container.addView(makeTextView(container, remaining, markwon))
            }
        }
    }

    fun renderStreaming(container: LinearLayout, content: String) {
        if (!content.contains("```") && !content.contains("|")) {
            val preprocessed = preprocessLatex(content)
            val lastView = container.getChildAt(container.childCount - 1)
            if (lastView is TextView) {
                markwon.setMarkdown(lastView, preprocessed)
            } else {
                container.removeAllViews()
                container.addView(makeTextView(container, preprocessed, markwon))
            }
            return
        }
        render(container, content)  // render() đã có removeAllViews() bên trong
    }


    // ── Data classes ────────────────────────────────────────────────

    private sealed class Block(val start: Int, val end: Int) {
        class Code(start: Int, end: Int, val language: String, val code: String) : Block(start, end)
        class Table(start: Int, end: Int, val header: List<String>, val rows: List<List<String>>) : Block(start, end)
        class Math(start: Int, end: Int, val latex: String) : Block(start, end)  // ← thêm
    }

    // ── Parse table ─────────────────────────────────────────────────

    private fun findBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()

        // Code blocks
        CODE_BLOCK_REGEX.findAll(text).forEach { match ->
            val language = match.groupValues[1].ifBlank { "code" }
            val code = match.groupValues[2].trimEnd('\n')
            blocks.add(Block.Code(match.range.first, match.range.last + 1, language, code))
        }

        // ✅ Math blocks \[...\]
        MATH_BLOCK_REGEX.findAll(text).forEach { match ->
            if (blocks.none { match.range.first >= it.start && match.range.last <= it.end }) {
                blocks.add(Block.Math(match.range.first, match.range.last + 1, match.groupValues[1].trim()))
            }
        }

        // ✅ Math blocks $$...$$
        MATH_BLOCK_DOLLAR_REGEX.findAll(text).forEach { match ->
            if (blocks.none { match.range.first >= it.start && match.range.last <= it.end }) {
                blocks.add(Block.Math(match.range.first, match.range.last + 1, match.groupValues[1].trim()))
            }
        }

        // Tables
        findTables(text).forEach { (start, end, header, rows) ->
            if (blocks.none { it is Block.Code && start >= it.start && end <= it.end }) {
                blocks.add(Block.Table(start, end, header, rows))
            }
        }

        return blocks
    }

    // ── Parse tables ────────────────────────────────────────────────

    private fun findTables(text: String): List<TableResult> {
        val results = mutableListOf<TableResult>()
        val lines = text.split("\n")
        var i = 0

        while (i < lines.size) {
            val tableLines = mutableListOf<String>()
            var j = i

            // ✅ Bắt đầu bảng khi gặp dòng có "|"
            while (j < lines.size && lines[j].trim().let {
                    it.startsWith("|") && it.contains("|") &&
                            // Loại trừ code block
                            !it.contains("```")
                }) {
                tableLines.add(lines[j])
                j++
            }

            // Nếu tìm thấy ít nhất 2 dòng bảng
            if (tableLines.size >= 2) {
                val tableText = tableLines.joinToString("\n")
                val parsed = parseTableLines(tableLines)

                if (parsed != null) {
                    val startIndex = text.indexOf(tableLines.first())
                    val endIndex = text.indexOf(tableLines.last()) + tableLines.last().length

                    if (startIndex >= 0 && endIndex > startIndex) {
                        results.add(TableResult(startIndex, endIndex, parsed.first, parsed.second))
                    }
                }
            }

            i = if (j > i) j else i + 1
        }

        return results
    }

    data class TableResult(
        val start: Int,
        val end: Int,
        val header: List<String>,
        val rows: List<List<String>>
    )

    // ✅ Parse từng dòng bảng
    private fun parseTableLines(lines: List<String>): Pair<List<String>, List<List<String>>>? {
        if (lines.isEmpty()) return null

        val allRows = mutableListOf<List<String>>()
        var headerRow: List<String>? = null

        for (line in lines) {
            // Bỏ qua dòng separator (|---|---|)
            if (line.matches(Regex("\\|\\s*[-: ]+\\s*\\|.*"))) {
                continue
            }

            // Bỏ qua dòng header markdown (##)
            if (line.trim().startsWith("#")) {
                continue
            }

            // Parse cells
            val cells = line.split("|")
                .map {
                    // ✅ Remove markdown formatting (**bold**, *italic*)
                    it.trim()
                        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                        .replace(Regex("\\*(.+?)\\*"), "$1")
                        .replace(Regex("`(.+?)`"), "$1")
                }
                .filter { it.isNotEmpty() }

            if (cells.isNotEmpty()) {
                if (headerRow == null) {
                    headerRow = cells
                } else {
                    allRows.add(cells)
                }
            }
        }

        return if (headerRow != null && allRows.isNotEmpty()) {
            Pair(headerRow, allRows)
        } else null
    }

    // ── Make views ──────────────────────────────────────────────────

    private fun makeTextView(container: LinearLayout, text: String, markwon: Markwon): TextView {
        return TextView(container.context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 15f
            setTextColor(0xFFE0E0F0.toInt())
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.3f)
            markwon.setMarkdown(this, text)
        }
    }

    private fun makeCodeBlock(container: LinearLayout, language: String, code: String): View {
        val binding = ItemCodeBlockBinding.inflate(
            LayoutInflater.from(container.context), container, false
        )
        binding.tvLanguage.text = language.ifBlank { "plaintext" }
        markwon.setMarkdown(binding.tvCode, "```$language\n$code\n```")

        // ── Copy ─────────────────────────────────────────────────────────────────
        binding.tvCopyLabel.setOnClickListener {
            val cb = container.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("code", code))
            binding.tvCopyLabel.text = "Copied!"
            binding.tvCopyLabel.setTextColor(0xFF3FB950.toInt())
            binding.ivCopyIcon.setColorFilter(0xFF3FB950.toInt())
            it.postDelayed({
                binding.tvCopyLabel.text = "Copy"
                binding.tvCopyLabel.setTextColor(0xFF8B949E.toInt())
                binding.ivCopyIcon.setColorFilter(0xFF8B949E.toInt())
            }, 2000)
        }
        // ── Run ──────────────────────────────────────────────────────────────────
        if (codeExecutor != null && language.lowercase() in CodeExecutor.SUPPORTED) {
            binding.footerRunBar.visibility = View.VISIBLE

            val blockId = RunStateManager.blockId(language, code)

            // Apply state ngay lập tức (sync)
            applyRunStateToFooter(binding, RunStateManager.getState(blockId))

            // Observe thay đổi trong khi view còn attached
            val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

            binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                private var job: Job? = null

                override fun onViewAttachedToWindow(v: View) {
                    // Bắt đầu observe khi view attach
                    job = RunStateManager.states
                        .map { it[blockId] ?: RunStateManager.RunState() }
                        .distinctUntilChanged()
                        .onEach { state -> applyRunStateToFooter(binding, state) }
                        .launchIn(scope)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    // Dừng observe khi view detach (tránh leak)
                    job?.cancel()
                    job = null
                }
            })

            // Click → mở bottom sheet
            binding.btnRunCode.setOnClickListener {
                showRunDialog(container.context, language, code, blockId)
            }
            // ── Zoom ─────────────────────────────────────────────────────────────────
            binding.btnZoomCode.setOnClickListener {
                showZoomDialog(container.context, language, code,blockId )
            }
        } else {
            binding.footerRunBar.visibility = View.GONE
        }

        return binding.root
    }

    /** Cập nhật label của ▶ Run button theo RunState hiện tại */
    private fun updateRunBtnState(binding: ItemCodeBlockBinding, blockId: String) {
        val state = RunStateManager.getState(blockId)
        val label = when (state.status) {
            RunStateManager.Status.Running -> "⏳ Running…"
            RunStateManager.Status.Done    -> when (state.result) {
                is CodeResult.Success -> "✅ Done"
                else                  -> "❌ Error"
            }
            else -> "▶  Run"
        }
        // Tìm TextView bên trong btnRunCode (chỉ có 1 TextView con)
        val tv = (binding.btnRunCode as? LinearLayout)?.getChildAt(0) as? TextView
        tv?.text = label
    }
    private fun applyRunStateToFooter(binding: ItemCodeBlockBinding, state: RunStateManager.RunState) {
        // Tìm TextView bên trong btnRunCode
        val tvRun = (binding.btnRunCode as? LinearLayout)
            ?.children
            ?.filterIsInstance<TextView>()
            ?.firstOrNull() ?: return

        when (state.status) {
            RunStateManager.Status.Idle -> {
                tvRun.text = "▶  Run"
                tvRun.setTextColor(0xFF3FB950.toInt())
                binding.btnRunCode.isEnabled = true
                binding.btnRunCode.alpha = 1f
            }

            RunStateManager.Status.Running -> {
                tvRun.text = "⏳  Running…"
                tvRun.setTextColor(0xFF8B949E.toInt())
                binding.btnRunCode.isEnabled = false
                binding.btnRunCode.alpha = 0.6f
            }

            RunStateManager.Status.Done -> {
                binding.btnRunCode.isEnabled = true
                binding.btnRunCode.alpha = 1f
                when (state.result) {
                    is CodeResult.Success -> {
                        // Hiện thêm thời gian nếu có
                        val timeLabel = state.time?.let { " · ${it}s" } ?: ""
                        tvRun.text = "✅  Done$timeLabel"
                        tvRun.setTextColor(0xFF3FB950.toInt())
                    }
                    is CodeResult.CompileError -> {
                        tvRun.text = "⚠️  Compile error"
                        tvRun.setTextColor(0xFFFFB347.toInt())
                    }
                    is CodeResult.RuntimeError -> {
                        tvRun.text = "❌  Runtime error"
                        tvRun.setTextColor(0xFFFF6B6B.toInt())
                    }
                    is CodeResult.Error -> {
                        tvRun.text = "❌  Error"
                        tvRun.setTextColor(0xFFFF6B6B.toInt())
                    }
                    null -> {
                        tvRun.text = "▶  Run"
                        tvRun.setTextColor(0xFF3FB950.toInt())
                    }
                }
            }
        }
    }


// ── Thay thế showRunDialog ───────────────────────────────────────────────────

    private fun showRunDialog(
        context: Context,
        language: String,
        code: String,
        blockId: String
    ) {
        val dialog = BottomSheetDialog(
            context, R.style.BottomSheetRunCodeTheme
        )
        val binding = BottomSheetRunCodeBinding.inflate(LayoutInflater.from(context))

        // Title
        binding.tvRunStatus.text = "▶  $language"

        // ── Restore state nếu đã có ─────────────────────────────────────────────
        fun applyState(state: RunStateManager.RunState) {
            binding.etStdin.setText(state.stdin)
            when (state.status) {
                RunStateManager.Status.Idle -> {
                    binding.layoutIdle.visibility = View.VISIBLE
                    binding.layoutRunning.visibility = View.GONE
                    binding.scrollOutput.visibility = View.GONE
                    binding.btnClearOutput.visibility = View.GONE
                    binding.layoutMeta.visibility = View.GONE
                    binding.tvBtnRunLabel.text = "▶  Run"
                    binding.btnRun.isEnabled = true
                    binding.progressRun.visibility = View.GONE
                }
                RunStateManager.Status.Running -> {
                    binding.layoutIdle.visibility = View.GONE
                    binding.layoutRunning.visibility = View.VISIBLE
                    binding.scrollOutput.visibility = View.GONE
                    binding.btnClearOutput.visibility = View.GONE
                    binding.layoutMeta.visibility = View.GONE
                    binding.tvBtnRunLabel.text = "⏳"
                    binding.progressRun.visibility = View.VISIBLE
                    binding.btnRun.isEnabled = false
                }
                RunStateManager.Status.Done -> {
                    val result = state.result ?: return
                    binding.layoutIdle.visibility = View.GONE
                    binding.layoutRunning.visibility = View.GONE
                    binding.scrollOutput.visibility = View.VISIBLE
                    binding.btnClearOutput.visibility = View.VISIBLE
                    binding.progressRun.visibility = View.GONE
                    binding.btnRun.isEnabled = true
                    binding.tvBtnRunLabel.text = "↺  Re-run"

                    when (result) {
                        is CodeResult.Success -> {
                            binding.tvRunStatus.text = "✅  $language"
                            binding.tvRunStatus.setTextColor(0xFF3FB950.toInt())
                            binding.tvRunOutput.text = result.output
                            binding.tvRunOutput.setTextColor(0xFFE6EDF3.toInt())
                            // Meta: time + memory
                            if (result.time != null || result.memory != null) {
                                binding.layoutMeta.visibility = View.VISIBLE
                                result.time?.let { binding.tvTime.text = "${it}s" }
                                result.memory?.let { binding.tvMemory.text = "${it / 1024} KB" }
                            }
                        }
                        is CodeResult.CompileError -> {
                            binding.tvRunStatus.text = "❌  Compile error"
                            binding.tvRunStatus.setTextColor(0xFFFF6B6B.toInt())
                            binding.tvRunOutput.text = result.error
                            binding.tvRunOutput.setTextColor(0xFFFF6B6B.toInt())
                            binding.layoutMeta.visibility = View.GONE
                        }
                        is CodeResult.RuntimeError -> {
                            binding.tvRunStatus.text = "⚠️  Runtime error"
                            binding.tvRunStatus.setTextColor(0xFFFFB347.toInt())
                            binding.tvRunOutput.text = result.error
                            binding.tvRunOutput.setTextColor(0xFFFFB347.toInt())
                            binding.layoutMeta.visibility = View.GONE
                        }
                        is CodeResult.Error -> {
                            binding.tvRunStatus.text = "❌  Error"
                            binding.tvRunStatus.setTextColor(0xFFFF6B6B.toInt())
                            binding.tvRunOutput.text = result.message
                            binding.tvRunOutput.setTextColor(0xFFFF6B6B.toInt())
                            binding.layoutMeta.visibility = View.GONE
                        }
                    }
                }
            }
        }

        // Apply ngay trạng thái hiện tại
        applyState(RunStateManager.getState(blockId))

        // ── Observe state thay đổi trong khi dialog mở ──────────────────────────
        val observeJob: Job = RunStateManager.states
            .onEach { map ->
                val s = map[blockId] ?: RunStateManager.RunState()
                applyState(s)
            }
            .launchIn(CoroutineScope(Dispatchers.Main))

        dialog.setOnDismissListener { observeJob.cancel() }

        // ── Run button ───────────────────────────────────────────────────────────
        binding.btnRun.setOnClickListener {
            val executor = codeExecutor ?: return@setOnClickListener
            val stdin = binding.etStdin.text.toString()

            RunStateManager.setRunning(blockId, language, code, stdin)

            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    executor.execute(language, code, stdin)
                }
                RunStateManager.setResult(blockId, result)
            }
        }

        // ── Clear ────────────────────────────────────────────────────────────────
        binding.btnClearOutput.setOnClickListener {
            RunStateManager.reset(blockId)
        }

        dialog.setContentView(binding.root)
        dialog.behavior.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
        }
        dialog.show()
    }

    // ✅ Thêm method makeTableBlock
    private fun makeTableBlock(
        container: LinearLayout,
        header: List<String>,
        rows: List<List<String>>
    ): View {
        val binding = ItemTableBinding.inflate(
            LayoutInflater.from(container.context),
            container,
            false
        )

        val context = container.context
        val tableLayout = binding.tableLayout

        // ✅ Set table properties
        tableLayout.apply {
            isShrinkAllColumns = false  // Không co cột
            isStretchAllColumns = true  // Giãn đều các cột
            removeAllViews()
        }

        // ✅ Tính max columns
        val maxColumns = maxOf(
            header.size,
            rows.maxOfOrNull { it.size } ?: 0
        )

        // Header row
        val headerRow = TableRow(context).apply {
            layoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            )
        }

        header.forEachIndexed { index, cell ->
            val cellView = createCellView(context, cell, isHeader = true, isEven = false)
            headerRow.addView(cellView)
        }
        tableLayout.addView(headerRow)

        // Divider gradient
        val divider = View(context).apply {
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.MATCH_PARENT,
                2
            )
            setBackgroundColor(Color.parseColor("#3B82F6"))
        }
        tableLayout.addView(divider)

        // Data rows
        rows.forEachIndexed { rowIndex, cells ->
            val tableRow = TableRow(context).apply {
                layoutParams = TableLayout.LayoutParams(
                    TableLayout.LayoutParams.MATCH_PARENT,
                    TableLayout.LayoutParams.WRAP_CONTENT
                )
            }

            cells.forEach { cell ->
                val cellView = createCellView(
                    context,
                    cell,
                    isHeader = false,
                    isEven = rowIndex % 2 == 0
                )
                tableRow.addView(cellView)
            }

            // Fill empty cells nếu row thiếu cột
            repeat(maxColumns - cells.size) {
                val emptyCell = createCellView(context, "", isHeader = false, isEven = rowIndex % 2 == 0)
                tableRow.addView(emptyCell)
            }

            tableLayout.addView(tableRow)
        }

        return binding.root
    }

    // ✅ Tạo cell view với style tối ưu
    private fun createCellView(
        context: Context,
        text: String,
        isHeader: Boolean,
        isEven: Boolean
    ): TextView {
        return TextView(context).apply {
            this.text = text.trim()

            // ✅ Padding hợp lý
            setPadding(12, 8, 12, 8)

            // ✅ Không wrap text → để HorizontalScrollView xử lý scroll
            maxLines = 1
            ellipsize = null  // Không dấu "..."
            setSingleLine(true)

            // ✅ Màu sắc
            if (isHeader) {
                setTextColor("#93C5FD".toColorInt())
                setBackgroundColor("#1E3A5F".toColorInt())
                setTypeface(typeface, Typeface.BOLD)
                textSize = 12f
                gravity = Gravity.CENTER
            } else {
                setTextColor("#E2E8F0".toColorInt())
                setBackgroundColor(
                    if (isEven) "#0F172A".toColorInt()
                    else "#1E293B".toColorInt()
                )
                textSize = 11f
                gravity = Gravity.START
            }

            typeface = Typeface.MONOSPACE

            // ✅ Border cho cell
            setPadding(12, 10, 12, 10)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showZoomDialog(context: Context, language: String, code: String, blockId: String) {
        val dialogBinding = DialogCodeZoomBinding.inflate(LayoutInflater.from(context))

        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setWindowAnimations(R.style.DialogZoomAnimation)
        }
        dialog.setContentView(dialogBinding.root)

        dialogBinding.tvZoomLanguage.text = language
        markwon.setMarkdown(dialogBinding.tvZoomCode, "```$language\n$code\n```")

        // Copy
        dialogBinding.btnZoomCopy.setOnClickListener {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("code", code))
            Toast.makeText(context, "✅ Code copied!", Toast.LENGTH_SHORT).show()
        }

        // ✅ Run button trong zoom dialog
        if (codeExecutor != null && language.lowercase() in CodeExecutor.SUPPORTED) {
            dialogBinding.btnZoomRun.visibility = View.VISIBLE
            dialogBinding.btnZoomRun.setOnClickListener {
                dialog.dismiss()
                showRunDialog(context, language,code, blockId = blockId)
            }
        } else {
            dialogBinding.btnZoomRun.visibility = View.GONE
        }

        // Close
        dialogBinding.btnZoomClose.setOnClickListener {
            dialog.dismiss()
        }

        // Double tap reset zoom
        val gestureDetector = android.view.GestureDetector(context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    dialogBinding.tvZoomCode.resetZoom()
                    return true
                }
            })

        dialogBinding.tvZoomCode.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        dialog.show()
    }

    // Make math block
    private fun makeMathBlock(container: LinearLayout, latex: String): View {
        val binding = ItemMathBlockBinding.inflate(
            LayoutInflater.from(container.context),
            container,
            false
        )

        val tvFormula = binding.tvMathFormula
        val tvCopyLabel = binding.tvCopyLatexLabel

        // ✅ Dùng $$ thay vì \[...\] vì blocksLegacy(true) chỉ đọc $$
        tvFormula.post {
            markwon.setMarkdown(tvFormula, "\$\$$latex\$\$")
        }

        binding.btnCopyLatex.setOnClickListener {
            val cb = container.context
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("latex", latex))
            tvCopyLabel.text = "Copied!"
            tvCopyLabel.setTextColor(0xFF3FB950.toInt())
            it.postDelayed({
                tvCopyLabel.text = "Copy LaTeX"
                tvCopyLabel.setTextColor(0xFF8B949E.toInt())
            }, 2000)
        }

        return binding.root
    }
}