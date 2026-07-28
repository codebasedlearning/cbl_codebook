// (C) 2026 A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

package dev.codebasedlearning.cbl

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.util.Key

/**
 * Applies fold regions for CBL comments programmatically via the FoldingModel.
 * This avoids registering a FoldingBuilder per language and therefore works in
 * any editor that produced PSI comments (C++, Python, Java, Kotlin, ...).
 *
 * Only the note BODY is folded, from the end of the frame line to the end of
 * the comment - the header line stays visible as ordinary source, so a folded
 * snippet reads as a table of contents interleaved with code. Two consequences
 * worth knowing:
 *  - the range sits strictly inside the IDE's own whole-comment fold region, so
 *    the two NEST instead of competing for an identical range and both gestures
 *    stay available to the reader;
 *  - single-line comments get no region at all.
 *
 * Every created region is tagged with [CBL_REGION]; [CBL_TITLE] carries the
 * block title, which keys the expansion state across re-parses (titles survive
 * the offset shifts caused by editing above a block, placeholders no longer
 * differ per block). The tags are a convenience, not the source of truth:
 * [regionsOf] also recognizes our regions by range, so a lost tag cannot make
 * the fold buttons go silent.
 */
object CblFolding {

    /**
     * Collapsed body marker; identical for every block by design. The fold
     * starts immediately after the frame line's last character, so the gap in
     * `---- Topic ---- ▸ …` can only come from the placeholder itself - and it
     * is a NON-BREAKING space (U+00A0), since a plain leading blank is not
     * reliably rendered.
     */
    const val PLACEHOLDER = "\u00A0▸ …"

    private val CBL_REGION = Key.create<Boolean>("cbl.fold.region")
    private val CBL_TITLE = Key.create<String>("cbl.fold.title")

    /**
     * Our regions in [editor]: everything we tagged, plus - belt and braces -
     * any region whose range matches a foldable block of [model]. The IDE's own
     * whole-comment regions have a different range and are never matched.
     */
    private fun regionsOf(editor: Editor, model: CblModel?): List<FoldRegion> {
        val ranges = model?.blocks?.filter { it.isFoldable }
            ?.map { it.foldStart to it.foldEnd }?.toSet() ?: emptySet()
        return editor.foldingModel.allFoldRegions.filter {
            it.isValid &&
                (it.getUserData(CBL_REGION) == true || (it.startOffset to it.endOffset) in ranges)
        }
    }

    /** Creates the regions and returns how many the folding model accepted. */
    fun apply(editor: Editor, model: CblModel): Int {
        val foldingModel = editor.foldingModel
        val caretOffset = editor.caretModel.offset
        var created = 0
        foldingModel.runBatchFoldingOperation {
            // preserve expansion state across re-applies, keyed by title
            val previousState = regionsOf(editor, model)
                .mapNotNull { region -> region.getUserData(CBL_TITLE)?.let { it to region.isExpanded } }
                .toMap()
            regionsOf(editor, model).forEach { foldingModel.removeFoldRegion(it) }

            for (block in model.blocks) {
                if (!block.isFoldable) continue // single-line comment
                val start = block.foldStart
                val end = block.foldEnd
                val region = foldingModel.addFoldRegion(start, end, PLACEHOLDER) ?: continue
                region.putUserData(CBL_REGION, true)
                region.putUserData(CBL_TITLE, block.title)
                created++
                // unknown (usually just-typed) blocks stay open when the
                // caret is inside them - never fold under the author's hands
                val caretInside = caretOffset in start..(end + 1)
                region.isExpanded = previousState[block.title] ?: caretInside
            }
        }
        return created
    }

    /**
     * Expand or collapse all CBL regions - a targeted subset that the IDE's
     * native Expand/Collapse All (which flattens code too) cannot express.
     * Returns false if there are no CBL regions in this editor.
     *
     * When expanding, enclosing FOREIGN regions are opened first: a CBL body
     * nested inside the IDE's collapsed whole-comment region would otherwise
     * expand invisibly, which looks exactly like a dead button.
     */
    fun setAll(editor: Editor, model: CblModel?, expanded: Boolean): Boolean {
        val regions = regionsOf(editor, model)
        if (regions.isEmpty()) return false
        editor.foldingModel.runBatchFoldingOperation {
            if (expanded) {
                for (outer in editor.foldingModel.allFoldRegions) {
                    if (outer.isExpanded || outer in regions) continue
                    val encloses = regions.any {
                        it.startOffset >= outer.startOffset && it.endOffset <= outer.endOffset
                    }
                    if (encloses) outer.isExpanded = true
                }
            }
            regions.forEach { it.isExpanded = expanded }
        }
        return true
    }

    /** Diagnostics for the fold buttons: how many regions we can currently see. */
    fun regionCount(editor: Editor, model: CblModel?): Int = regionsOf(editor, model).size

    /** How many of them the platform reports as collapsed. */
    fun collapsedCount(editor: Editor, model: CblModel?): Int =
        regionsOf(editor, model).count { !it.isExpanded }
}
