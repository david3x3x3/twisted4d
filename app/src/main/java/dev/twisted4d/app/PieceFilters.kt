package dev.twisted4d.app

/** Number of a piece's [HypercubeGeometry.HOME_POSITIONS] coordinates that are nonzero -- its
 * permanent piece-type identity (1=center/2=ridge/3=edge/4=corner, community terminology for an
 * N^4 puzzle), independent of the piece's current position/orientation. Shared by [PieceFilter]'s
 * token matching and [HypercubeRenderer]'s per-piece dimming (previously duplicated inline in
 * both places). */
val Vec4i.stickerCount: Int
    get() = (if (x != 0) 1 else 0) + (if (y != 0) 1 else 0) + (if (z != 0) 1 else 0) + (if (w != 0) 1 else 0)

/** A single letter-group's matching rule -- one dash-separated piece of a token (see
 * [parsePieceGroup]'s doc for which of the three shapes a given group string resolves to). */
private sealed interface PieceGroup {
    fun matches(homeCoords: IntArray, stickerCount: Int): Boolean
}

/** `"IUFe"`-style: cell letters intersected (a piece must touch every one of [cells]) and, if
 * [requiredStickerCount] is set, narrowed to that one piece type -- the original grammar's only
 * shape, unchanged. */
private data class CellIntersectionGroup(val cells: List<Cell4>, val requiredStickerCount: Int?) : PieceGroup {
    override fun matches(homeCoords: IntArray, stickerCount: Int): Boolean =
        (requiredStickerCount == null || requiredStickerCount == stickerCount) &&
            cells.all { homeCoords[it.axis.nativeIndex] == it.sign }
}

/** `"LR"`-style: a bare run of *only* cell letters, with no type letter -- a piece matches if it
 * touches *any* of [cells] (union), not all of them -- e.g. `LR` means "touches L or R", not the
 * (always-empty, since no piece touches two opposite cells) "touches both L and R". */
private data class CellUnionGroup(val cells: List<Cell4>) : PieceGroup {
    override fun matches(homeCoords: IntArray, stickerCount: Int): Boolean =
        cells.any { homeCoords[it.axis.nativeIndex] == it.sign }
}

/** `"mre"`-style: a bare run of *only* type letters -- a piece matches if its type is any one of
 * [stickerCounts] (union) -- e.g. `mre` means "a center, ridge, or edge", not the always-empty
 * intersection (no piece is simultaneously more than one type). */
private data class TypeUnionGroup(val stickerCounts: Set<Int>) : PieceGroup {
    override fun matches(homeCoords: IntArray, stickerCount: Int): Boolean = stickerCount in stickerCounts
}

private val TYPE_LETTER_STICKER_COUNT: Map<Char, Int> = mapOf('m' to 1, 'r' to 2, 'e' to 3, 'c' to 4)
private val CELL_BY_LETTER: Map<Char, Cell4> = Cell4.entries.associateBy { it.label.single() }

/** One clause within a subfilter string, already split from its comma-separated siblings, and
 * further split on `-` (see [parseSubfilterTokens]'s doc): matches a piece that matches [positive]
 * and none of [removals] -- e.g. `"mre-LR"` is `positive=TypeUnionGroup({1,2,3})`,
 * `removals=[CellUnionGroup([L,R])]`. A token with no `-` at all just has an empty [removals]. */
private data class FilterToken(val positive: PieceGroup, val removals: List<PieceGroup>) {
    fun matches(homeCoords: IntArray, stickerCount: Int): Boolean =
        positive.matches(homeCoords, stickerCount) && removals.none { it.matches(homeCoords, stickerCount) }
}

/** Resolves one dash-separated group string (from within a token, see [parseSubfilterTokens]) to
 * a [PieceGroup], by shape:
 * - every character a cell letter (`U D L R F B I O`) -> [CellUnionGroup] (union)
 * - every character a type letter (`m r e c`) -> [TypeUnionGroup] (union)
 * - one or more cell letters followed by exactly one trailing type letter (the original grammar's
 *   only shape, e.g. `IUFe`) -> [CellIntersectionGroup] (intersection of cells, AND that type)
 *
 * Anything else (a type letter not trailing, cell and type letters mixed in some other order,
 * more than one trailing type letter, etc.) is rejected -- those shapes aren't assigned a meaning. */
private fun parsePieceGroup(group: String, token: String): PieceGroup {
    require(group.isNotEmpty()) { "empty group in token \"$token\"" }
    return when {
        group.all { TYPE_LETTER_STICKER_COUNT.containsKey(it) } ->
            TypeUnionGroup(group.map { TYPE_LETTER_STICKER_COUNT.getValue(it) }.toSet())
        group.all { CELL_BY_LETTER.containsKey(it) } ->
            CellUnionGroup(group.map { CELL_BY_LETTER.getValue(it) })
        else -> {
            val requiredStickerCount = TYPE_LETTER_STICKER_COUNT[group.last()]
                ?: throw IllegalArgumentException(
                    "invalid group \"$group\" in token \"$token\": not all cell letters, not all type " +
                        "letters, and doesn't end in a type letter after cell letters",
                )
            val cells = group.dropLast(1).map { ch ->
                CELL_BY_LETTER[ch] ?: throw IllegalArgumentException("unknown cell letter '$ch' in token \"$token\"")
            }
            CellIntersectionGroup(cells, requiredStickerCount)
        }
    }
}

/** Parses one subfilter string's comma-separated tokens (see [PieceFilters]' class doc for the
 * full grammar), each further split on `-` into a positive group and zero or more groups to
 * subtract from it (chained -- `"mre-L-R"` and `"mre-LR"` remove the same thing, either as two
 * separate subtractions or one union-shaped one). Tokens themselves are unioned together for the
 * subfilter as a whole -- unchanged from the original grammar. */
private fun parseSubfilterTokens(subfilter: String): List<FilterToken> =
    subfilter.split(',').map { rawToken ->
        val token = rawToken.trim()
        require(token.isNotEmpty()) { "empty token in subfilter \"$subfilter\"" }
        val groups = token.split('-').map { parsePieceGroup(it, token) }
        FilterToken(positive = groups.first(), removals = groups.drop(1))
    }

/** One subfilter after splitting off its leading `"+"` (see [PieceFilter]'s doc): [additive]
 * means "union with whatever was visible just before this step" rather than resetting to just
 * this subfilter's own matches. */
private data class ParsedSubfilter(val additive: Boolean, val tokens: List<FilterToken>)

private fun parseSubfilter(raw: String): ParsedSubfilter {
    val additive = raw.startsWith("+")
    val text = if (additive) raw.substring(1) else raw
    return ParsedSubfilter(additive, parseSubfilterTokens(text))
}

/**
 * One *filter*: an ordered list of subfilter expression strings (see [PieceFilters]' class doc
 * for the grammar), one stage of a [PieceFilterSet]. Stepping into a filter starts at
 * `subfilters[0]`; each step forward reveals `subfilters[step]`'s own matches, *plus* whatever
 * was visible just before it if `subfilters[step]` starts with `"+"` (union/accumulate) -- a
 * subfilter with no `"+"` resets to just its own matches instead, clearing anything shown by
 * earlier subfilters. This is a uniform per-subfilter rule with no special case for a filter's
 * own `subfilters[0]`: whether crossing into a *new* filter clears the previous filter's pieces
 * is decided the exact same way, by whether the new filter's first subfilter starts with `"+"`
 * (see [PieceFilterSet.isPieceVisible] for how that carry-in across filters is resolved) --
 * deliberately decoupling "this step gets a new label" (which filter you're in) from "this step
 * clears prior pieces" (whether its first subfilter has "+"), which used to be the same event
 * (crossing a filter boundary always reset) and didn't need to be.
 *
 * See [MainActivity]'s Select+L1/L2 wiring (which steps through a filter, and past its last
 * subfilter into the filter-set's next filter) for how stepping is surfaced.
 */
data class PieceFilter(val name: String, val subfilters: List<String>) {
    // Parsed eagerly, once per (immutable) PieceFilter instance, rather than per-frame --
    // HypercubeRenderer calls isPieceVisible for every piece, every frame a filter is active, and
    // re-splitting/re-parsing every subfilter string that often would be wasted work. Eager
    // (rather than `by lazy`) specifically so a malformed token (e.g. an unknown cell letter)
    // surfaces immediately as an exception from construction/parseFilterText -- callers importing
    // untrusted text (MainActivity's clipboard import) need to catch a bad paste *before*
    // committing it, not discover it later when the renderer first evaluates the filter.
    private val parsedSubfilters: List<ParsedSubfilter> = subfilters.map(::parseSubfilter)

    /** The index of the nearest subfilter at or before [clampedStep] that does NOT start with
     * `"+"` -- i.e. where this filter's own *local* accumulation window starts. Falls back to 0
     * (the whole local range) if every subfilter through [clampedStep] is additive -- that case
     * means this filter alone can't resolve visibility; see [needsCarryIn]. */
    private fun localWindowStart(clampedStep: Int): Int {
        for (i in clampedStep downTo 0) {
            if (!parsedSubfilters[i].additive) return i
        }
        return 0
    }

    /** True if [home] (a piece's permanent HOME_POSITIONS identity) is matched by this filter's
     * own subfilters alone, once stepped to [step] (clamped into range; a freshly-selected filter
     * starts at step 0) -- unions `subfilters[localWindowStart(step)..step]`, i.e. everything back
     * to the nearest non-additive ("reset") subfilter. Doesn't see anything from a *previous*
     * filter even if [needsCarryIn] is true for [step] -- that's [PieceFilterSet.isPieceVisible]'s
     * job, since only it knows what filter came before this one. */
    fun isPieceVisible(step: Int, home: Vec4i): Boolean {
        if (parsedSubfilters.isEmpty()) return false
        val homeCoords = intArrayOf(home.x, home.y, home.z, home.w)
        val stickerCount = home.stickerCount
        val clampedStep = step.coerceIn(0, parsedSubfilters.size - 1)
        val windowStart = localWindowStart(clampedStep)
        for (i in windowStart..clampedStep) {
            if (parsedSubfilters[i].tokens.any { it.matches(homeCoords, stickerCount) }) return true
        }
        return false
    }

    /** True if every subfilter from 0 through [step] (clamped) starts with `"+"` -- i.e. this
     * filter never hits a "reset" subfilter on the way back to its own start, so resolving full
     * visibility at [step] needs reaching back into whatever filter preceded this one in its
     * [PieceFilterSet] (or, if this is already the set's first filter, there's nothing to reach
     * back into and a leading "+" there is simply a no-op). */
    fun needsCarryIn(step: Int): Boolean {
        if (parsedSubfilters.isEmpty()) return false
        val clampedStep = step.coerceIn(0, parsedSubfilters.size - 1)
        return (0..clampedStep).all { parsedSubfilters[it].additive }
    }
}

/**
 * One named group of [PieceFilter]s, selected as a unit from the Filters menu -- e.g. `pieces`,
 * `cfop`, or `3block`. Stepping (Select+L1/L2 in STICK mode) moves through `filters[0].
 * subfilters`, then on reaching its last subfilter, the *next* press moves into `filters[1]`'s
 * own subfilters starting fresh at its step 0 (see [MainActivity].updateFilterStatusText's doc).
 * Whether that crossing also *clears* what was visible at the end of `filters[0]` is controlled
 * by [PieceFilter]'s `"+"` grammar, exactly the same way it's controlled between two subfilters
 * inside one filter -- crossing into a new filter is not itself special-cased. By default (no
 * `"+"` on the new filter's first subfilter) it does reset: David wants filter-sets like `3block`,
 * which walk through many named solve stages, to be able to stop showing an earlier stage's
 * pieces once you've moved past it and they're just visual clutter -- unlike `cfop`, whose single
 * filter wants everything solved so far to stay visible. A filter-set author gets that "starts
 * fresh" behavior for free by simply putting each stage in its own [PieceFilter] and not
 * prefixing its first subfilter with `"+"`; if a later filter *should* keep showing what an
 * earlier one revealed, prefixing its first subfilter with `"+"` carries that forward without
 * having to re-list the earlier tokens by hand (the whole point of decoupling "new filter, new
 * label" from "new filter, clears prior pieces" -- see [PieceFilter]'s doc).
 */
data class PieceFilterSet(val name: String, val filters: List<PieceFilter>) {
    /** Whether [home] is visible once stepped to ([filterIndex], [step]) (both clamped into
     * range). First checks `filters[filterIndex]` alone ([PieceFilter.isPieceVisible]); if that
     * filter never finds a local reset point on the way back to its own subfilter 0 (i.e.
     * [PieceFilter.needsCarryIn] is true at [step]), recurses into the *previous* filter's own
     * last step to pull in whatever it had accumulated -- which may itself recurse further back,
     * chaining across as many consecutive all-additive filters as the definition uses. Grounds
     * out (returns false) at [filterIndex] 0 needing carry-in, since there's no earlier filter to
     * reach into -- a leading `"+"` on a filter-set's very first subfilter is a harmless no-op. */
    fun isPieceVisible(filterIndex: Int, step: Int, home: Vec4i): Boolean {
        if (filters.isEmpty()) return false
        val clampedIndex = filterIndex.coerceIn(0, filters.size - 1)
        val filter = filters[clampedIndex]
        val clampedStep = step.coerceIn(0, filter.subfilters.size - 1)
        if (filter.isPieceVisible(clampedStep, home)) return true
        if (clampedIndex > 0 && filter.needsCarryIn(clampedStep)) {
            val prev = filters[clampedIndex - 1]
            return isPieceVisible(clampedIndex - 1, prev.subfilters.size - 1, home)
        }
        return false
    }
}

private val FILTER_SET_HEADER_LINE = Regex("""^(\S.*):\s*$""")
private val FILTER_HEADER_LINE = Regex("""^\s+(\S.*):\s*$""")
private val SUBFILTER_LINE = Regex("""^\s*-\s*"(.*)"\s*$""")

/**
 * Piece filters and their hand-editable text format.
 *
 * A filter definition file/clipboard blob is a small hand-rolled format (not real YAML -- no
 * such parsing library exists in this project, and YAML's generality buys nothing here; `org.json`
 * is already used elsewhere for save files, but JSON's quoting is worse to hand-edit than this),
 * three levels deep -- filter-set, filter, subfilter -- shaped like:
 * ```
 * # comment lines start with '#', blank lines ignored
 * pieces:
 *   pieces:
 *     - "m"
 *     - "+r"
 *
 * 3block:
 *   cross:
 *     - "m,Ir"
 *   left cross:
 *     - "+IUFe,UFr"
 * ```
 * (the leading `"+"` on `"+r"`/`"+IUFe,UFr"` above means each accumulates onto what the previous
 * step showed, rather than resetting -- see the `"+"` paragraph below.)
 * An unindented `name:` line starts a filter-set; an indented `name:` line (any amount of leading
 * whitespace -- not pinned to a specific width, so tabs or a different number of spaces both
 * work) starts a filter within it; an indented `- "subfilter"` line (distinguished from a filter
 * header by its leading `-`, not by indent depth either) appends one subfilter string to it.
 *
 * A subfilter string optionally starts with `"+"` (see [PieceFilter]'s doc -- stepping to this
 * subfilter unions its matches with whatever was visible one step before, instead of the default
 * of resetting to just this subfilter's own matches; the same rule applies uniformly whether the
 * previous step was another subfilter in this filter or the last subfilter of the *previous*
 * filter, so a filter's label can change independently of whether its pieces get cleared). After
 * that optional `"+"`, the rest is comma-separated tokens (union), each token optionally
 * dash-separated into a positive group and one or more groups to subtract from it (e.g.
 * `"mre-LR"` = every center/ridge/edge piece, except any touching L or R). Each group -- on
 * either side of a `-` -- is one of: cell letters intersected, optionally narrowed to one type by
 * a single trailing type letter (`"IUFe"` = the one piece touching I, U, *and* F, that's also an
 * edge -- the original grammar's only shape); a bare run of *only* cell letters, unioned (`"LR"` =
 * touches L *or* R, not the always-empty "touches both"); or a bare run of *only* type letters,
 * unioned (`"mre"` = a center, ridge, *or* edge, not the always-empty "is simultaneously all
 * three"). See [parsePieceGroup]/[parseSubfilterTokens] for the exact rules, and [PieceFilterSet]
 * for why a filter-set can hold more than one filter and what crossing from one into the next
 * actually does.
 *
 * [MainActivity] persists the *raw text* of the currently-active filter set (starting from
 * [BUILTIN_FILTERS_TEXT]) rather than a re-serialized structure, so clipboard export is always
 * byte-for-byte what was last imported/edited -- no round-trip reformatting to worry about.
 */
object PieceFilters {

    /** Throws [IllegalArgumentException] with a line-numbered message on malformed input
     * (including an empty filter-set or filter, which [PieceFilter.isPieceVisible]'s step-
     * clamping can't handle) -- callers importing user-supplied text (MainActivity's clipboard
     * import) should catch this and leave the existing filter set untouched rather than letting a
     * bad paste corrupt it. */
    fun parseFilterText(text: String): List<PieceFilterSet> {
        val filterSets = mutableListOf<PieceFilterSet>()
        var currentSetName: String? = null
        var currentFilters = mutableListOf<PieceFilter>()
        var currentFilterName: String? = null
        var currentSubfilters = mutableListOf<String>()

        fun flushFilter() {
            val name = currentFilterName ?: return
            require(currentSubfilters.isNotEmpty()) { "filter \"$name\" has no subfilters" }
            currentFilters.add(PieceFilter(name, currentSubfilters))
            currentFilterName = null
            currentSubfilters = mutableListOf()
        }

        fun flushFilterSet() {
            flushFilter()
            val name = currentSetName ?: return
            require(currentFilters.isNotEmpty()) { "filter-set \"$name\" has no filters" }
            filterSets.add(PieceFilterSet(name, currentFilters))
            currentSetName = null
            currentFilters = mutableListOf()
        }

        text.lines().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.substringBefore('#').trimEnd()
            if (line.isBlank()) return@forEachIndexed

            // Checked before either header pattern -- a subfilter line's leading `-` is what
            // distinguishes it, not its indent depth, and a header regex alone can't tell "  - "x""
            // apart from a filter header without this ordering.
            val subfilterLine = SUBFILTER_LINE.matchEntire(line)
            if (subfilterLine != null) {
                require(currentFilterName != null) {
                    "line $lineNumber: subfilter before any filter header: $rawLine"
                }
                currentSubfilters.add(subfilterLine.groupValues[1])
                return@forEachIndexed
            }

            val filterSetHeader = FILTER_SET_HEADER_LINE.matchEntire(line)
            if (filterSetHeader != null) {
                flushFilterSet()
                currentSetName = filterSetHeader.groupValues[1]
                return@forEachIndexed
            }

            val filterHeader = FILTER_HEADER_LINE.matchEntire(line)
            require(filterHeader != null) {
                "line $lineNumber: expected a filter-set header, a filter header, or a '- \"subfilter\"' line, got: $rawLine"
            }
            require(currentSetName != null) {
                "line $lineNumber: filter header before any filter-set header: $rawLine"
            }
            flushFilter()
            currentFilterName = filterHeader.groupValues[1]
        }
        flushFilterSet()
        require(filterSets.isNotEmpty()) { "no filter sets found" }
        return filterSets
    }

    /** The filter sets shipped at launch. `pieces` and `cfop` each wrap David's original flat
     * filters unchanged, as a filter-set containing a single same-named filter (so
     * [MainActivity]'s status text, which now shows the *filter's* name rather than the
     * filter-set's, reads exactly as it did before this became 3 levels deep) -- `pieces` is the
     * old 4 independent toggles (Centers/Ridges/3c-Edges/4c-Corners) re-expressed as 4
     * subfilters; `cfop` reveals one "next pair to solve" at a time per David's own 4D-CFOP
     * ordering: the I-cell cross first, then each U/D-ring ridge+edge pair, then each corner+its
     * adjacent edge, then O's ridges/edges/corners last, one piece type at a time (split from a
     * single catch-all "O" step).
     *
     * Lives in `app/src/main/resources/piece_filters.txt`, a plain text file David can open and
     * edit directly, rather than an inline Kotlin string -- deliberately a JVM classpath
     * resource (`Class.getResourceAsStream`), not an Android `res/raw` or `assets/` file: those
     * both need a `Context` to read, which would force BUILTIN_FILTER_SETS/BUILTIN_FILTERS_TEXT
     * to stop being plain top-level vals and break PieceFiltersTest (plain JVM unit tests, no
     * Android framework/Robolectric available there). Parsed through [parseFilterText] rather
     * than hand-built as Kotlin data, so this file is exercised by the same code path as
     * clipboard import and doubles as the canonical example format to copy when hand-writing new
     * filter sets. */
    val BUILTIN_FILTERS_TEXT: String = PieceFilters::class.java.getResourceAsStream("/piece_filters.txt")
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("piece_filters.txt is missing from the classpath -- expected at app/src/main/resources/piece_filters.txt")

    val BUILTIN_FILTER_SETS: List<PieceFilterSet> = parseFilterText(BUILTIN_FILTERS_TEXT)
}
