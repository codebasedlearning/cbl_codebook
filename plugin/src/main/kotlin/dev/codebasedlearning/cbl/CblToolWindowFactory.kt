// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLDocument

class CblToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(factory.createContent(CblPanel(project), "", false))
        project.service<CblService>().refresh()
    }
}

/**
 * Indent per nesting level, shared by BOTH lists the panel shows: the outline
 * above (a JList with hand-placed bullets) and the Markdown lists in the notes
 * pane below (Swing HTML, which would otherwise use its own defaults of 50 for
 * the first level and 25 per level after it). One number, so the two read as
 * one document instead of two widgets that happen to sit on top of each other.
 *
 * Caveat: the outline scales it through JBUI, the stylesheet cannot - CSS
 * lengths in a Swing sheet are what they are. On a HiDPI display the two drift
 * apart by the scale factor; noticeable only at 200% and up.
 */
private const val LEVEL_INDENT = 14

/** Left inset of a first-level bullet in the outline. */
private const val BULLET_INSET = 6

/**
 * The notes pane's first-level list margin. Swing paints the bullet INSIDE this
 * margin, `-bullet-gap` (10 by default) to the left of the text, so the bullet
 * lands at roughly [BULLET_INSET] - which is where the outline puts its own.
 */
private const val LIST_MARGIN = BULLET_INSET + 10

/**
 * Air between two list items in the notes pane. Swing sets list items to zero
 * margins, which is denser than the outline above and denser than the notes'
 * own paragraph rhythm - one screen, two spacings. Taken OUT of the list's top
 * margin again (see the stylesheet), so only the inside of a list changes.
 */
private const val ITEM_GAP = 4

/** Extensions the panel treats as reading material - looked up in the peek
 *  frame rather than opened in the editor (see `CblPanel.togglePeek`). */
private val markdownExtensions = setOf("md", "markdown")

class CblPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = project.service<CblService>()

    private val titleLabel = JBLabel()
    private val tocModel = CollectionListModel<CblBlock>()
    private val tocList = JBList(tocModel)
    private val notesPane = htmlPane()
    // AutoscrollFromSource = the platform's own "selection follows editor"
    // icon (Structure/Project view) - familiar semantics for free
    private val followToggle = selectableToggle(
        AllIcons.General.AutoscrollFromSource,
        "Follow caret: selected topic tracks the CBL comment above the caret; " +
            "off = selection stays put while working in the code"
    )
    private val upperPanel: JPanel
    private val splitter: JBSplitter
    private var syncingSelection = false

    /** Explicitly unfolded ![](#ref) embeds; default is folded (lecture
     *  mode: reveal on demand). Keyed by the ref as written - the same ref
     *  in several blocks toggles consistently. */
    private val expandedEmbeds = mutableSetOf<String>()

    /** Last rendered block, for re-rendering after an embed toggle. */
    private var currentDetail: CblBlock? = null

    /**
     * The peeked block, shown in a frame BELOW the notes, and the ref that
     * opened it. Clicking a Markdown link no longer opens that file in the
     * editor - a lookup is not navigation, and reading one definition should
     * not cost a tab and the place you were reading. Clicking the same link
     * again closes the peek (the link is the toggle, which is why the frame
     * needs no close button); clicking a link INSIDE the peek replaces its
     * content, because that click carries a different ref.
     *
     * One slot, no history: a chain deep enough to want a back button has not
     * happened yet, and the stack can be added here without touching anything
     * else. Code refs are unaffected - see [openLink].
     */
    private var peek: CblMarkdown.Resolved? = null
    private var peekRef: String? = null

    /**
     * Title of the block the panel currently shows - the selection's identity
     * across re-parses and across refreshes that momentarily leave the list
     * empty. Kept in the panel rather than read back from [tocList], because
     * the list is exactly what those refreshes clear (see [updateModel]).
     * Titles, not offsets: offsets shift on every edit above a block, and the
     * fold state keys on titles for the same reason.
     */
    private var selectedTitle: String? = null

    init {
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            add(compact(iconButton(AllIcons.Actions.Execute, "Run current file") {
                runSelectedConfiguration(DefaultRunExecutor.getRunExecutorInstance())
            }))
            add(compact(iconButton(AllIcons.Actions.StartDebugger, "Debug current file") {
                runSelectedConfiguration(DefaultDebugExecutor.getDebugExecutorInstance())
            }))
            add(compact(followToggle))
            // folds/unfolds ONLY the CBL comments - a targeted subset the IDE's
            // native Expand/Collapse All (which flattens everything) cannot express
            add(compact(iconButton(AllIcons.Actions.Expandall, "Unfold all CBL comments") { setFolds(expand = true) }))
            add(compact(iconButton(AllIcons.Actions.Collapseall, "Fold all CBL comments") { setFolds(expand = false) }))
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(titleLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        upperPanel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(tocList).apply {
                // wrapping owns the width - never scroll sideways; vertical
                // scrolling only kicks in when the 70% height clamp cuts off
                horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
        splitter = JBSplitter(true, 0.3f).apply {
            firstComponent = upperPanel
            // the vertical bar is ALWAYS there: appearing on demand takes ~14px
            // off the width, and every paragraph in the pane re-wraps when it
            // does - a peek opening must not reformat the text above it
            secondComponent = JBScrollPane(notesPane).apply {
                verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
            }
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = adjustSplitter()
            })
        }
        add(splitter, BorderLayout.CENTER)

        tocList.cellRenderer = WrappingRenderer()
        tocList.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                // invalidate the list's cell-height cache so wraps recompute
                tocList.setFixedCellHeight(10)
                tocList.setFixedCellHeight(-1)
            }
        })
        tocList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (syncingSelection) return
                val block = tocList.selectedValue ?: return
                // follow-caret on: click jumps (panel and caret are coupled
                // anyway). Off: single click only selects here in the panel,
                // the editor stays where it is; double click jumps explicitly.
                if (followToggle.isSelected || e.clickCount >= 2) navigateTo(block)
                // render notes directly - with follow-caret off, the caret
                // listener won't do it for us
                showBlock(block)
            }
        })

        notesPane.addHyperlinkListener { event ->
            if (event.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                openLink(event.description ?: event.url?.toExternalForm() ?: return@addHyperlinkListener)
            }
        }

        service.addModelListener { updateModel() }
        service.addCaretListener { offset ->
            if (followToggle.isSelected) updateCaret(offset)
        }
        followToggle.isSelected = true // follow-caret is the default
        followToggle.addActionListener {
            // switching follow back on: sync to the current caret immediately
            if (followToggle.isSelected) {
                FileEditorManager.getInstance(project).selectedTextEditor
                    ?.caretModel?.offset?.let { updateCaret(it) }
            }
        }
        updateModel()
    }

    private fun updateModel() {
        val model = service.model
        val fileName = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.name
        titleLabel.text = "<html><b>${escape(fileName ?: "–")}</b></html>"
        syncingSelection = true
        try {
            // dotted titles are unlisted: they exist as blocks (notes,
            // breadcrumb, folding, refs) but stay out of the TOC
            tocModel.replaceAll(model?.listedBlocks ?: emptyList())
            // a re-parse must not wipe the selection - essential with
            // follow-caret off, where it is the only state there is. The
            // remembered title, NOT the list's own selection, is what restores
            // it: a refresh that finds no editor (a run console holding the
            // focus, a switch to a non-code tab) empties the list first, and
            // reading the selection back out of it would then find nothing.
            // Matched against ALL blocks, so an unlisted one keeps its notes on
            // screen - it just has no row to highlight.
            val restored = model?.blocks?.firstOrNull { it.title == selectedTitle }
            if (restored != null) {
                if (restored.isUnlisted) tocList.clearSelection()
                else tocList.setSelectedValue(restored, true)
                showBlock(restored)
            } else {
                // no match: say so explicitly. Leaving it to the list means
                // leaving it to Swing, and an arbitrary row (the first) then
                // looks like a selection the reader never made.
                tocList.clearSelection()
                notesPane.text = ""
            }
        } finally {
            syncingSelection = false
        }
        SwingUtilities.invokeLater { adjustSplitter() }
    }

    /** Size the upper pane to its content so the notes start directly below the TOC. */
    private fun adjustSplitter() {
        val total = splitter.height
        if (total <= 0) return
        val desired = upperPanel.preferredSize.height + JBUI.scale(8)
        splitter.proportion = (desired.toFloat() / total).coerceIn(0.1f, 0.7f)
    }

    private fun updateCaret(offset: Int) {
        val model = service.model ?: return
        val block = model.blockAt(offset)
        syncingSelection = true
        try {
            if (block != null) {
                // an unlisted block has no row to highlight - the notes still
                // follow the caret, the TOC just has nothing to say about it
                if (block.isUnlisted) tocList.clearSelection()
                else tocList.setSelectedValue(block, true)
                showBlock(block)
            } else {
                // the caret left every block - drop the remembered title too,
                // or the next refresh would resurrect a selection the reader
                // has moved away from
                selectedTitle = null
                tocList.clearSelection()
                notesPane.text = ""
            }
        } finally {
            syncingSelection = false
        }
    }

    /**
     * Layered notes, stacked in ONE scrollable pane (deliberately no second
     * splitter - typographic separation instead of another movable divider):
     * on top the enclosing depth-1 topic as the sticky discussion context,
     * below it the innermost block under the caret, titled with its gray
     * breadcrumb chain. Caret in the topic itself (or a depth-1 [detail])
     * renders the topic section only.
     */
    private fun showBlock(detail: CblBlock, preserveScroll: Boolean = false) {
        // a lookup belongs to the block it was looked up from: moving on closes
        // it. Compared by title, not by identity - a re-parse hands out new
        // block objects for the same file, and typing must not close the peek.
        if (detail.title != currentDetail?.title) closePeek()
        currentDetail = detail
        selectedTitle = detail.title
        val chain = service.model?.chainOf(detail) ?: emptyList()
        // ALL layers of the chain contribute, topic -> ... -> detail
        val layers = (chain + detail).distinct()
        // single header: the breadcrumb (one line, normal title color) - the
        // bodies below carry no headers of their own. Each crumb carries its
        // own level emphasis, exactly like the TOC: bold topic, italic child,
        // plain below - so no outer <b> here.
        //
        // Unlisted blocks are skipped: the breadcrumb names the path a reader
        // could have walked in the outline, and a name that is nowhere in the
        // list above is not a step of that path - it only raises the question
        // where it came from. Their BODIES still stack (that is what an
        // unlisted block is for), the trail just does not mention them.
        val crumbs = layers.filter { !it.isUnlisted }
            .joinToString(" &#9656; ") { levelEmphasis(inlineTitle(it), it.depth) }
        val html = StringBuilder("<html><body style='margin:6px 8px'>")
        // nothing listed on the way here - no trail, and no rule under it
        if (crumbs.isNotEmpty()) html.append(crumbs).append("<hr>")
        // bodies in chain order, each behind a thin gray divider (div.detail
        // border-top; NOT an <hr> - Swing's HRuleView paints black and
        // ignores css margins). Empty bodies are skipped entirely, and no
        // divider is drawn when there is nothing above it to separate from.
        //
        // An UNLISTED block heads its own section: it is named neither in the
        // outline nor in the breadcrumb, so without this its notes would appear
        // from nowhere, attributed to whatever crumb happens to stand above.
        // Listed blocks say nothing here - the breadcrumb has already named
        // them, and a title twice on one screen is one too many.
        var first = true
        for (block in layers) {
            val heading =
                if (block.isUnlisted) "<p>${levelEmphasis(inlineTitle(block), block.depth)}</p>" else ""
            if (heading.isEmpty() && !hasContent(block)) continue
            val section = heading + if (hasContent(block)) renderBody(block) else ""
            if (first) html.append(section)
            else html.append("<div class='detail'>").append(section).append("</div>")
            first = false
        }
        // the looked-up block, last and framed: borrowed text, visibly not part
        // of this file's notes. Its header says where it came from and offers
        // the editor as an explicit choice rather than as the default.
        peek?.let { target ->
            // header as a two-cell table: Swing has no float, and a table is the
            // only construct its HTML engine right-aligns reliably
            html.append("<div class='peek'>")
                .append("<table class='peekbar' width='100%'><tr><td class='peekwhere'>")
                .append("<a href='${CblMarkdown.OPEN_SCHEME}${peekRef}'>&#9873;</a> ")
                .append(escape(target.path ?: "")).append(" &#9656; ")
                .append(inlineTitle(target.block))
                .append("</td><td class='peekclose' align='right'>")
                .append("<a href='${CblMarkdown.CLOSE_SCHEME}'>&#10005;</a>")
                .append("</td></tr></table>")
                .append(renderForeignBody(target))
                .append("</div>")
        }
        html.append("</body></html>")
        applyImageBase()
        // embed toggles re-render in place - keep the scroll position then,
        // otherwise (new block) jump to the top
        val viewport = notesPane.parent as? javax.swing.JViewport
        val scrollPosition = if (preserveScroll) viewport?.viewPosition else null
        notesPane.text = html.toString()
        if (scrollPosition != null) {
            SwingUtilities.invokeLater { viewport?.viewPosition = scrollPosition }
        } else {
            notesPane.caretPosition = 0
        }
    }

    private fun hasContent(block: CblBlock): Boolean = block.bodyLines.any { it.isNotBlank() }

    private fun inlineTitle(block: CblBlock): String =
        CblMarkdown.inlineToHtml(block.title.ifBlank { "(untitled)" })

    /** Markdown body -> HTML, with ![](#ref)/![](path#ref) embeds resolved. */
    private fun renderBody(block: CblBlock): String {
        // short refs first: they become long forms, so the embed pass and the
        // hyperlink listener never learn about them
        val body = CblMarkdown.expandShortcuts(
            block.bodyLines.joinToString("\n"), glossaryPath()
        ) { destination -> resolveRefTarget(destination)?.block?.title }
        val md = CblMarkdown.resolveEmbeds(
            body,
            expanded = { ref -> ref in expandedEmbeds },
        ) { ref -> resolveRefTarget(ref) }
        return rewriteImageSrc(CblMarkdown.softenCodeBlocks(CblMarkdown.toHtml(md)))
    }

    /**
     * Body of a PEEKED block. Same pipeline as [renderBody], minus the short-ref
     * expansion (that one resolves against the current file's glossary path, not
     * against the peeked file's) and plus the foreign rebasing, so links inside
     * the lookup point at the file that wrote them.
     */
    private fun renderForeignBody(target: CblMarkdown.Resolved): String {
        val md = CblMarkdown.resolveEmbeds(
            target.block.bodyLines.joinToString("\n"),
            expanded = { ref -> ref in expandedEmbeds },
        ) { ref -> resolveRefTarget(ref) }
        return rewriteImageSrc(
            CblMarkdown.softenCodeBlocks(CblMarkdown.toHtml(CblMarkdown.rebaseForeign(md, target)))
        )
    }

    private fun closePeek() {
        peek = null
        peekRef = null
    }

    /**
     * Show [target] in the peek frame, or close it if the same ref is clicked
     * again - the link that opened the lookup is the one that dismisses it.
     * Returns false when the destination is not a peekable one, in which case
     * [openLink] falls back to opening the file.
     */
    private fun togglePeek(target: String): Boolean {
        val hash = target.indexOf('#')
        if (hash <= 0) return false                     // same-file refs keep their behaviour
        if (peekRef == target) {
            closePeek()
            currentDetail?.let { showBlock(it, preserveScroll = true) }
            return true
        }
        val dir = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.parent ?: return false
        val path = target.substring(0, hash)
        val file = dir.findFileByRelativePath(path) ?: return false
        // Markdown is reading material and is peeked; code is where the reader
        // works, so a code ref still takes the editor there
        if (file.extension?.lowercase() !in markdownExtensions) return false
        val block = foreignModel(file)?.blockByRef(target.substring(hash + 1)) ?: return false
        peek = CblMarkdown.Resolved(block, java.io.File(file.parent.path), path)
        peekRef = target
        currentDetail?.let { showBlock(it, preserveScroll = true) }
        // the frame is appended at the bottom, so scroll there - a lookup that
        // lands below the fold looks like a click that did nothing
        SwingUtilities.invokeLater { notesPane.caretPosition = notesPane.document.length }
        return true
    }

    private fun iconButton(icon: javax.swing.Icon, tip: String, action: () -> Unit) =
        JButton(icon).apply {
            toolTipText = tip
            addActionListener { action() }
        }

    /** Icon-only, tight-fitting button styling. */
    private fun compact(button: AbstractButton): AbstractButton = button.apply {
        margin = JBUI.emptyInsets()
        isFocusable = false
        preferredSize = JBUI.size(26, 26)
    }

    /**
     * Icon-only toggle with an explicit selected state (tint + accent border) -
     * the IDE LaF paints almost none for icon-only JToggleButtons.
     */
    private fun selectableToggle(icon: javax.swing.Icon, tip: String): JToggleButton =
        JToggleButton(icon).apply {
            toolTipText = tip
            val defaultBorder = border
            addItemListener {
                if (isSelected) {
                    isOpaque = true
                    background = com.intellij.ui.JBColor(java.awt.Color(213, 227, 250), java.awt.Color(47, 69, 98))
                    border = javax.swing.BorderFactory.createLineBorder(
                        com.intellij.ui.JBColor(java.awt.Color(53, 116, 240), java.awt.Color(76, 115, 180)), 1, true
                    )
                } else {
                    isOpaque = false
                    background = null
                    border = defaultBorder
                }
                repaint()
            }
        }

    /**
     * Level emphasis in the TOC, on top of bullet and indent: **bold** topics,
     * *italic* children, plain from level 3 down - three visual weights are
     * what a glance can still tell apart, and the indent carries the rest.
     * Applied around the rendered title, so Markdown emphasis inside a title
     * (`**Content**`) simply nests.
     */
    private fun levelEmphasis(html: String, depth: Int): String = when (depth) {
        1 -> "<b>$html</b>"
        2 -> "<i>$html</i>"
        else -> html
    }

    /**
     * List cell renderer with wrapping and hanging indent: the bullet sits in
     * a fixed left column, continuation lines align under the title text -
     * matching how the HTML notes pane wraps list items.
     */
    private inner class WrappingRenderer : JPanel(BorderLayout()), ListCellRenderer<CblBlock> {
        private val bullet = JBLabel().apply {
            verticalAlignment = javax.swing.SwingConstants.TOP
            border = JBUI.Borders.empty(2, 6, 2, 0)
            font = com.intellij.util.ui.UIUtil.getLabelFont()
        }
        private val title = JEditorPane("text/html", "").apply {
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            border = JBUI.Borders.empty(2, 4, 2, 6)
            font = com.intellij.util.ui.UIUtil.getLabelFont()
        }

        init {
            isOpaque = true
            add(bullet, BorderLayout.WEST)
            add(title, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out CblBlock>, value: CblBlock, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean,
        ): Component {
            bullet.text = when (value.depth) {
                1 -> "•"
                2 -> "◦"
                else -> "·"
            }
            bullet.border = JBUI.Borders.empty(2, BULLET_INSET + (value.depth - 1) * LEVEL_INDENT, 2, 0)
            title.text =
                "<html><body style='margin:0'>${levelEmphasis(value.titleHtml, value.depth)}</body></html>"
            val textWidth = list.width - bullet.preferredSize.width - JBUI.scale(12)
            if (textWidth > 0) title.setSize(textWidth, Short.MAX_VALUE.toInt())
            background = if (isSelected) list.selectionBackground else list.background
            val fg = if (isSelected) list.selectionForeground else list.foreground
            bullet.foreground = fg
            title.foreground = fg
            return this
        }
    }

    private fun setFolds(expand: Boolean) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            Messages.showInfoMessage(project, "No editor selected.", "CBL")
            return
        }
        val model = service.model
        if (CblFolding.setAll(editor, model, expand)) return

        val blocks = model?.blocks ?: emptyList()
        val foldable = blocks.count { it.isFoldable }
        if (foldable > 0 && model != null) {
            val created = CblFolding.apply(editor, model)
            if (CblFolding.setAll(editor, model, expand)) return
            Messages.showInfoMessage(
                project,
                "$foldable foldable block(s) in the model, but the editor accepted " +
                    "$created fold region(s) and reports " +
                    "${CblFolding.regionCount(editor, model)} of ours.\n" +
                    "The folding model refused the ranges - please report this " +
                    "together with the file.",
                "CBL"
            )
            return
        }
        Messages.showInfoMessage(
            project,
            if (blocks.isNotEmpty())
                "All ${blocks.size} CBL block(s) in this file are single-line comments -\n" +
                    "there is no note body to fold away."
            else
                "Parser sees 0 blocks in the current file.\n" +
                    "If the file clearly contains CBL comments, the language's " +
                    "PSI probably provides no comment tokens here (CLion Nova?) - " +
                    "try Settings > Advanced Settings > classic C/C++ language engine.",
            "CBL"
        )
    }

    private fun navigateTo(block: CblBlock) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        if (block.startOffset > editor.document.textLength) return
        // navigation only - fold state is deliberately left untouched
        editor.caretModel.moveToOffset(block.startOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    /** See [CblRun]: derives a configuration from the current file's context. */
    private fun runSelectedConfiguration(executor: Executor) =
        CblRun.runCurrentFile(project, executor)

    /**
     * The `glossary.path` entries as paths relative to the current file - the
     * form every existing ref mechanism already handles, so the short forms
     * need no scheme of their own. Empty without a configured path.
     */
    private fun glossaryPath(): List<String> {
        val source = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return emptyList()
        val dir = source.parent ?: return emptyList()
        return CblConfig.forFile(project, source).glossaryPath.mapNotNull {
            CblConfig.relativePath(dir, it)
        }
    }

    /**
     * Model of a REFERENCED file, under a read action - `CblForeign` walks the
     * PSI for code files. `Application.runReadAction` deliberately: the Kotlin
     * `runReadAction` extension and `ReadAction.compute` are both deprecated
     * from 2026.1, and their replacement `computeBlocking` does not exist on the
     * 242 baseline - this is what it delegates to.
     */
    private fun foreignModel(file: com.intellij.openapi.vfs.VirtualFile): CblModel? =
        ApplicationManager.getApplication().runReadAction(
            Computable<CblModel?> { CblForeign.model(project, file) }
        )

    /**
     * Resolve a ref destination: "#frag" against the current file's model,
     * "path#frag" against the referenced file (PSI comments for code,
     * headings for markdown - see [CblForeign]).
     */
    private fun resolveRefTarget(ref: String): CblMarkdown.Resolved? {
        val hash = ref.indexOf('#')
        if (hash < 0) return null
        val fragment = ref.substring(hash + 1)
        if (hash == 0) {
            return service.model?.blockByRef(fragment)?.let { CblMarkdown.Resolved(it) }
        }
        val dir = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.parent ?: return null
        val path = ref.substring(0, hash)
        val file = dir.findFileByRelativePath(path) ?: return null
        val block = foreignModel(file)?.blockByRef(fragment) ?: return null
        // the path as WRITTEN travels with the target: links inside the
        // embedded body are rewritten against it, so a click lands in the file
        // that meant them (see CblMarkdown.rebaseLinks)
        return CblMarkdown.Resolved(block, java.io.File(file.parent.path), path)
    }

    /**
     * Rewrite relative img src attributes to absolute file URLs, resolved
     * against the folder of the current file. Runs on the generated HTML
     * string, before Swing parses it (ImageView resolves src at parse time).
     */
    private fun rewriteImageSrc(html: String): String {
        val dir = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.parent ?: return html
        return Regex("src=\"([^\"]+)\"").replace(html) { match ->
            val src = match.groupValues[1]
            if ("://" in src || src.startsWith("data:")) return@replace match.value
            val file = java.io.File(dir.path, src)
            if (file.isFile) "src=\"${file.toURI()}\"" else match.value
        }
    }

    /** Web links open in the browser; #fragments resolve as block references;
     *  relative paths open as files in the editor. */
    private fun openLink(target: String) {
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
            com.intellij.ide.BrowserUtil.browse(target)
            return
        }
        if (target.startsWith(CblMarkdown.TOGGLE_SCHEME)) {
            val ref = target.removePrefix(CblMarkdown.TOGGLE_SCHEME)
            if (!expandedEmbeds.remove(ref)) expandedEmbeds.add(ref)
            currentDetail?.let { showBlock(it, preserveScroll = true) }
            return
        }
        if (target.startsWith(CblMarkdown.CLOSE_SCHEME)) {
            closePeek()
            currentDetail?.let { showBlock(it, preserveScroll = true) }
            return
        }
        if (target.startsWith(CblMarkdown.OPEN_SCHEME)) {
            // the peek header's explicit "take me there"
            openInEditor(target.removePrefix(CblMarkdown.OPEN_SCHEME))
            return
        }
        if (target.startsWith("#")) {
            val block = service.model?.blockByRef(target.removePrefix("#")) ?: return
            tocList.setSelectedValue(block, true)
            showBlock(block)
            // editor jump obeys the same policy as TOC clicks: only when
            // follow-caret couples panel and editor anyway
            if (followToggle.isSelected) navigateTo(block)
            return
        }
        val hash = target.indexOf('#')
        if (hash > 0) {
            // Markdown: look it up in the peek, right here. Code, or anything
            // that does not resolve: open the file at the block, as before.
            if (togglePeek(target)) return
            openInEditor(target)
            return
        }
        val dir = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.parent ?: return
        val file = java.io.File(dir.path, target)
        if (file.isFile) {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)?.let {
                com.intellij.openapi.fileEditor.OpenFileDescriptor(project, it).navigate(true)
            }
        }
    }

    /** Open `path#fragment` in the editor, at the block if the fragment
     *  resolves, at the file start otherwise. */
    private fun openInEditor(target: String) {
        val hash = target.indexOf('#')
        if (hash <= 0) return
        val dir = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.parent ?: return
        val file = dir.findFileByRelativePath(target.substring(0, hash)) ?: return
        val block = foreignModel(file)?.blockByRef(target.substring(hash + 1))
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, block?.startOffset ?: 0)
            .navigate(true)
    }

    /** Fallback base for anything the rewrite left relative. */
    private fun applyImageBase() {
        val dir = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.parent ?: return
        val base = VfsUtilCore.convertToURL(dir.url) ?: return
        (notesPane.document as? HTMLDocument)?.base = base
    }

    private fun escape(s: String): String = StringUtil.escapeXmlEntities(s)

    private fun htmlPane() = JEditorPane().apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        // private stylesheet with ONE uniform vertical rhythm - Swing's
        // defaults give p and hr asymmetric margins. Subclass + private
        // copy: HTMLEditorKit.setStyleSheet would modify the app-global one.
        editorKit = object : javax.swing.text.html.HTMLEditorKit() {
            private var custom: javax.swing.text.html.StyleSheet? = null
            override fun getStyleSheet(): javax.swing.text.html.StyleSheet =
                custom ?: javax.swing.text.html.StyleSheet().also {
                    val hex = com.intellij.ui.ColorUtil.toHex(
                        com.intellij.util.ui.UIUtil.getContextHelpForeground()
                    )
                    it.addStyleSheet(super.getStyleSheet())
                    it.addRule("p { margin-top: 8px; margin-bottom: 0px; }")
                    // divider above the caret-detail body
                    it.addRule("div.detail { margin-top: 8px; border-top: 1px solid #$hex; }")
                    // embedded blocks: framed by the same thin gray lines
                    it.addRule("div.embed { margin-top: 8px; padding-bottom: 8px; " +
                        "border-top: 1px solid #$hex; border-bottom: 1px solid #$hex; }")
                    // the peek frame: borrowed text, so a heavier top rule than
                    // an embed and a gray provenance line above it
                    it.addRule("div.peek { margin-top: 12px; padding-bottom: 8px; " +
                        "border-top: 2px solid #$hex; }")
                    it.addRule("p.peekhead { margin-top: 6px; margin-bottom: 2px; color: #$hex; }")
                    // the peek's header bar - a table only because Swing cannot
                    // right-align anything else; no cell padding, no rules
                    it.addRule("table.peekbar { margin-top: 4px; }")
                    it.addRule("td.peekwhere { padding: 0px; color: #$hex; }")
                    it.addRule("td.peekclose { padding: 0px; text-align: right; }")
                    // code blocks: monospace, but wrapping - see
                    // CblMarkdown.softenCodeBlocks for why they are not <pre>
                    it.addRule("div.code { font-family: Monospaced; margin-top: 8px; }")
                    /*
                     * Lists: the 8px rhythm AROUND a list, none INSIDE it.
                     * Swing's default.css says `ul { margin-top: 10;
                     * margin-bottom: 10 }` and its nested rule (`ul li ul`)
                     * changes only the bullet and the left margin - so a
                     * SUB-LIST inherits both margins and arrives with 10 above
                     * it and 10 below, wider than any gap elsewhere on the page
                     * and enough to make the next first-level item read as a
                     * new list. Overriding the nested selectors is enough:
                     * `ul li` and `ul li p` are already zero over there, and the
                     * item spacing was never the problem.
                     *
                     * The left margins come from the same [LEVEL_INDENT] the
                     * outline steps by, instead of Swing's 50-then-25 - two
                     * lists one above the other must not indent differently.
                     *
                     * One selector per rule, everything a selector needs in
                     * ONE rule - Swing's CSS parser is not worth trusting with
                     * selector groups, and a second rule for the same selector
                     * merges rather than replaces.
                     */
                    val outer = "margin-top: ${8 - ITEM_GAP}px; margin-bottom: 0px; " +
                        "margin-left-ltr: $LIST_MARGIN;"
                    val inner = "margin-top: 0px; margin-bottom: 0px; margin-left-ltr: $LEVEL_INDENT;"
                    val item = "margin-top: ${ITEM_GAP}px; margin-bottom: 0px;"
                    val itemText = "margin-top: 0px; margin-bottom: 0px;"
                    it.addRule("ul { $outer }")
                    it.addRule("ol { $outer }")
                    it.addRule("ul ul { $inner }")
                    it.addRule("ul ol { $inner }")
                    it.addRule("ol ul { $inner }")
                    it.addRule("ol ol { $inner }")
                    /*
                     * ... and the same margins again under Swing's OWN nested
                     * selectors. `ul li ul` and `ul li ul li ul` are what
                     * default.css uses for levels 2 and 3, and they declare a
                     * left margin of 25; three and five element names beat our
                     * two, so should the cascade ever go by specificity rather
                     * than by sheet order, the sub-lists would keep Swing's
                     * indent and only the vertical fix would land. (The `ol`
                     * side needs no counterpart - default.css defines no nested
                     * rules for it.)
                     */
                    it.addRule("ul li ul { $inner }")
                    it.addRule("ul li ul li ul { $inner }")
                    /*
                     * Items breathe a little: [ITEM_GAP] above each one, which
                     * the list's own top margin gives back, so the gap BEFORE a
                     * list stays the page's 8px while the gap BETWEEN items no
                     * longer reads denser than the outline above.
                     */
                    it.addRule("ul li { $item }")
                    it.addRule("ol li { $item }")
                    it.addRule("ul li ul li { $item }")
                    /*
                     * A LOOSE list (blank line between items in the source)
                     * wraps every item in <p>, and our own `p` rule up there
                     * would then add its 8px to each - which is why one list
                     * sat noticeably lower under its lead-in text than the tight
                     * list above it. Item text carries no paragraph rhythm.
                     */
                    it.addRule("li p { $itemText }")
                    it.addRule("ul li p { $itemText }")
                    it.addRule("ol li p { $itemText }")
                    // GFM tables: Swing draws no rules and collapses nothing, so
                    // the columns need explicit breathing room and a header line
                    it.addRule("table { margin-top: 8px; }")
                    it.addRule("th { text-align: left; padding: 0px 12px 2px 0px; " +
                        "border-bottom: 1px solid #$hex; }")
                    it.addRule("td { text-align: left; padding: 2px 12px 0px 0px; vertical-align: top; }")
                    custom = it
                }
        }
        contentType = "text/html"
    }
}
