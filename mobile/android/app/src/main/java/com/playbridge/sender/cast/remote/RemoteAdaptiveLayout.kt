package com.playbridge.sender.cast.remote

enum class RemoteSection { Header, Info, Toolbar, Input, Seek, Nav }

data class RemoteAdaptiveSections(
    val info: Boolean = false,
    val upNext: Boolean = false,
    val toolbar: Boolean = false,
    val input: Boolean = true,
    val seek: Boolean = false,
    val inputPresentation: RemoteInputPresentation = RemoteInputPresentation.Full,
)

/** Actual Compose measurements replace estimates independently; collapsing never overwrites the expanded size. */
data class RemoteSectionHeights(
    val header: Float? = null,
    val info: Float? = null,
    val upNext: Float? = null,
    val compactUpNext: Float? = null,
    val toolbar: Float? = null,
    val seek: Float? = null,
    val nav: Float? = null,
)

/** How a wide viewport divides the body above the pinned controls. */
enum class RemoteColumnSplit {
    /** Portrait, or a wide viewport that still stacks. */
    Stack,
    /** Full INPUT stays on the left; header, info and toolbar stay on the right. */
    InputLeft,
    /** No full INPUT: info and linkage on the left, Up next and a compact pad on the right. */
    InfoAndQueue,
}

data class RemoteAdaptiveLayout(
    val columns: Int,
    val split: RemoteColumnSplit,
    val collapseUpNext: Boolean,
    val scrollUpper: Boolean,
    val inputHeightDp: Float,
    val minimumInputHeightDp: Float,
    val pinnedHeightDp: Float,
    /** Height of each side column when [split] is [RemoteColumnSplit.InfoAndQueue]; otherwise 0. */
    val columnBodyDp: Float,
    val sectionOrder: List<RemoteSection>,
) {
    companion object {
        const val GAP_DP = 8f

        /** Android-free geometry policy. Viewport height is after safe drawing/IME insets and outer padding. */
        fun resolve(
            viewport: RemoteViewport,
            sections: RemoteAdaptiveSections,
            measured: RemoteSectionHeights = RemoteSectionHeights(),
        ): RemoteAdaptiveLayout {
            val hasInput = sections.input && sections.inputPresentation != RemoteInputPresentation.Absent
            val font = viewport.fontScale.coerceAtLeast(1f)
            fun height(actual: Float?, estimate: Float) = (actual ?: estimate).coerceAtLeast(0f)
            val header = height(measured.header, 64f + 24f * (font - 1f))
            val info = if (sections.info) height(measured.info, 120f * font) else 0f
            val toolbar = if (sections.toolbar) height(measured.toolbar, 80f * font) else 0f
            // Collapsed content cannot report its expanded size. Use a dp fallback,
            // never a font-multiplied height; expanded content supplies its actual measurement.
            val queue = if (sections.upNext) height(measured.upNext, 160f) else 0f
            val compactQueue = if (sections.upNext) height(measured.compactUpNext, 48f) else 0f
            val seek = if (sections.seek) height(measured.seek, 104f * font) else 0f
            val nav = height(measured.nav, 48f)
            val pinned = nav + GAP_DP +
                (if (sections.seek) seek + GAP_DP else 0f)
            val upperGaps = GAP_DP * ((if (hasInput) 1 else 0) + (if (sections.info) 1 else 0) +
                (if (sections.toolbar) 1 else 0) + (if (sections.upNext) 1 else 0))
            val available = (viewport.heightDp - viewport.insetDp).coerceAtLeast(0).toFloat()
            // The minimum belongs to the entire INPUT section. Content and its
            // controls/padding are inside this budget.
            val minimum = if (!hasInput) 0f else viewport.minimumInputHeightDp.coerceAtLeast(160).toFloat()
            val wide = viewport.landscape || viewport.widthDp > viewport.heightDp
            // Full INPUT keeps the pad-left landscape. No INPUT uses a second pair of
            // columns so info is not stacked on top of Up next.
            val padColumn = wide && hasInput
            val infoQueue = wide && !padColumn
            val withoutQueue = available - pinned - header - info - toolbar - upperGaps
            val collapse = sections.upNext && !infoQueue && if (padColumn) {
                withoutQueue - queue < 0f
            } else {
                withoutQueue - queue < minimum
            }
            val remaining = withoutQueue - if (collapse) compactQueue else queue
            val input = if (!hasInput) 0f else if (padColumn) (available - nav - GAP_DP).coerceAtLeast(minimum) else remaining.coerceAtLeast(minimum)
            // InfoAndQueue screen: header, then the row, then seek, inside a column
            // whose bottom padding is nav + one gap. Matches RemoteScreen.
            val betweenRows = GAP_DP * (1 + if (sections.seek) 1 else 0)
            val columnGaps = if (infoQueue) betweenRows + nav + GAP_DP else 0f
            val columnBody = if (!infoQueue) 0f else (available - header - (if (sections.seek) seek else 0f) - columnGaps).coerceAtLeast(0f)
            return RemoteAdaptiveLayout(
                columns = if (padColumn || infoQueue) 2 else 1,
                split = when {
                    padColumn -> RemoteColumnSplit.InputLeft
                    infoQueue -> RemoteColumnSplit.InfoAndQueue
                    else -> RemoteColumnSplit.Stack
                },
                collapseUpNext = collapse,
                scrollUpper = when {
                    padColumn -> remaining < 0f
                    infoQueue -> false
                    else -> remaining < minimum
                },
                inputHeightDp = input,
                minimumInputHeightDp = minimum,
                pinnedHeightDp = pinned,
                columnBodyDp = columnBody,
                sectionOrder = buildList {
                    add(RemoteSection.Header)
                    if (sections.info || sections.upNext) add(RemoteSection.Info)
                    if (sections.toolbar) add(RemoteSection.Toolbar)
                    if (hasInput) add(RemoteSection.Input)
                    if (sections.seek) add(RemoteSection.Seek)
                    add(RemoteSection.Nav)
                },
            )
        }
    }
}
