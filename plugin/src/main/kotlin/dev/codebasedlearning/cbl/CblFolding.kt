// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

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
 * Regions are RECONCILED, never rebuilt (see [apply]): a region that still
 * covers a block's body is the same region, whatever happened to the block's
 * title in the meantime. Every created region is tagged with [CBL_REGION]. The
 * tag is a convenience, not the source of truth: [regionsOf] also recognizes
 * our regions by range, so a lost tag cannot make the fold buttons go silent.
 */
object CblFolding {

    /**
     * Collapsed body marker; identical for every block by design. The fold
     * starts immediately after the frame line's last character, so the gap in
     * `---- Topic ---- ▸ …` can only come from the placeholder itself - and it
     * is a NON-BREAKING space (U+00A0), since a plain leading blank is not
     * reliably rendered.
     */
    const val PLACEHOLDER = " ▸ …"

    private val CBL_REGION = Key.create<Boolean>("cbl.fold.region")

    /** Set on an editor once [apply] has run for it - see there. */
    private val CBL_SEEN = Key.create<Boolean>("cbl.fold.seen")

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

    /**
     * Brings the editor's CBL regions in line with [model] and returns how many
     * blocks own a region afterwards.
     *
     * RECONCILE, do not rebuild. Fold regions are range markers: they move with
     * every edit, so after a re-parse the region of an untouched block still
     * covers exactly that block's body - and a region that does is KEPT, with
     * whatever state the reader gave it. Only two things change anything:
     *  - a region no block answers to any more is removed;
     *  - a block without a region gets a new one.
     *
     * Until v2.2.1 every re-parse removed all regions and re-created them, with
     * the expansion state carried over BY TITLE. Three things followed, all of
     * them folding text away under the author's hands:
     *  - a comment that had just become a block, or a block whose title was
     *    being edited, had a title nobody had seen yet, so it fell through to
     *    "is the caret in the body?" - and a caret on the frame line is not;
     *  - the same happened when the caret had already moved on within the
     *    debounce;
     *  - two blocks with the same title (`-- .Preview --`) shared one state.
     * And a collapse with the caret inside does not leave the caret where it
     * was - the platform moves it out, to the start of the region.
     *
     * The state of a NEW region:
     *  - the first time this editor is seen (a file being opened): collapsed,
     *    the presentation default - unless the caret is in the body;
     *  - afterwards: expanded. A block that appears in an editor we already
     *    know appeared because someone is writing it. Nothing folds as a
     *    consequence of typing. (If the block's old region was merely knocked
     *    out of shape by the edit - Enter at the end of a frame line does that -
     *    its state is inherited, unless the caret is in that very comment.)
     */
    fun apply(editor: Editor, model: CblModel): Int {
        val foldingModel = editor.foldingModel
        val caret = editor.caretModel.offset
        val firstSight = editor.getUserData(CBL_SEEN) != true
        var live = 0
        foldingModel.runBatchFoldingOperation {
            val existing = regionsOf(editor, model)
            val byRange = HashMap<Pair<Int, Int>, FoldRegion>()
            for (region in existing) byRange.putIfAbsent(region.startOffset to region.endOffset, region)

            // 1. blocks whose region is still in place keep it, state included
            val kept = HashSet<FoldRegion>()
            val pending = ArrayList<CblBlock>()
            for (block in model.blocks) {
                if (!block.isFoldable) continue // single-line comment
                val region = byRange[block.foldStart to block.foldEnd]
                if (region != null && kept.add(region)) {
                    region.putUserData(CBL_REGION, true)
                    live++
                } else {
                    pending.add(block)
                }
            }

            // 2. regions nothing answers to go - but remember what they were,
            //    for a block whose region only lost its exact range
            val orphans = existing.filter { it !in kept }
            val orphanStates = orphans.map { Triple(it.startOffset, it.endOffset, it.isExpanded) }
            orphans.forEach { foldingModel.removeFoldRegion(it) }

            // 3. new regions for the rest
            for (block in pending) {
                val start = block.foldStart
                val end = block.foldEnd
                val region = foldingModel.addFoldRegion(start, end, PLACEHOLDER) ?: continue
                region.putUserData(CBL_REGION, true)
                live++
                region.isExpanded = if (firstSight) {
                    caret in start..(end + 1)
                } else {
                    // headerRange is the range of the whole comment, frame line included
                    val caretInComment =
                        caret >= block.headerRange.startOffset && caret <= block.headerRange.endOffset
                    val inherited = orphanStates.firstOrNull { (s, e, _) -> s < end && e > start }?.third
                    caretInComment || (inherited ?: true)
                }
            }
        }
        editor.putUserData(CBL_SEEN, true)
        return live
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
}
