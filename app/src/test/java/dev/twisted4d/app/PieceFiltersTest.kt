package dev.twisted4d.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for [PieceFilters]/[PieceFilter]/[PieceFilterSet] -- the hand-rolled
 * filter-definition format (three levels: filter-set, filter, subfilter) and its cell-letter/
 * type-letter token grammar (see PieceFilters' class doc), which is easy to get subtly wrong
 * (intersection vs. union, which axis/sign a cell letter means, sticker-count disambiguation,
 * which indent level a header belongs to) without any compiler help. */
class PieceFiltersTest {

    // --- BUILTIN_FILTERS_TEXT parses into the two expected filter sets -------------------------

    @Test
    fun `built-in text parses into pieces, cfop, and 3block filter sets, in order`() {
        val filterSets = PieceFilters.BUILTIN_FILTER_SETS
        assertEquals(listOf("pieces", "cfop", "3block"), filterSets.map { it.name })
        // pieces wraps David's original flat filter unchanged, as a single same-named filter.
        assertEquals(listOf("pieces"), filterSets[0].filters.map { it.name })
        // "+" on every subfilter after the first preserves the original cumulative-stepping
        // behavior (see PieceFilter's "+" doc) -- the raw text round-trips with it included.
        assertEquals(listOf("m", "+r", "+e", "+c"), filterSets[0].filters[0].subfilters)
        // cfop's original single 24-subfilter "cfop" filter was later split (1638bb5) into 7
        // named stages -- cross, f2l-a white/equator/yellow, f2l-b white/yellow, ll -- so each
        // step's label matches exactly what it reveals; still 24 subfilters total across all 7.
        assertEquals(
            listOf("cross", "f2l-a white", "f2l-a equator", "f2l-a yellow", "f2l-b white", "f2l-b yellow", "ll"),
            filterSets[1].filters.map { it.name },
        )
        assertEquals(24, filterSets[1].filters.sumOf { it.subfilters.size })
        // 22 original steps with a single catch-all "O" at the end, split (by David) into three
        // separate Or/Oe/Oc steps -- one per piece type -- now the "ll" filter's own subfilters.
        assertEquals(listOf("+Or", "+Oe", "+Oc"), filterSets[1].filters.last().subfilters)
    }

    @Test
    fun `3block filter set has the expected filters, in order`() {
        val threeBlock = PieceFilters.BUILTIN_FILTER_SETS.first { it.name == "3block" }
        assertEquals(
            listOf(
                "centers", "cross", "mid back", "mid front", "left cross", "left mid",
                "left back a", "left back b", "left front a", "left front b",
                "right cross", "right mid", "right back a", "right back b", "right front a", "right front b",
                "olc 2c", "olc 3c", "olc 4c", "plc 2c", "plc cross", "plc f2l", "plc ll", "end",
            ),
            threeBlock.filters.map { it.name },
        )
    }

    @Test
    fun `3block's centers and cross filters accumulate every center, then the 4 ridges in O's non-LR ring`() {
        // The original single "mid" filter (subfilter 0 = every center, subfilter 1 = "+" the
        // O-ring ridges) was later split (1638bb5) into two separate filters, "centers" and
        // "cross" -- "cross"'s own leading "+" carries "centers"' pieces forward across that
        // filter boundary instead, same cumulative behavior as before.
        val threeBlock = PieceFilters.BUILTIN_FILTER_SETS.first { it.name == "3block" }
        val centersIndex = threeBlock.filters.indexOfFirst { it.name == "centers" }
        val crossIndex = threeBlock.filters.indexOfFirst { it.name == "cross" }
        // Every center, regardless of cell -- the "centers" filter alone.
        assertTrue(threeBlock.isPieceVisible(centersIndex, 0, Vec4i(1, 0, 0, 0))) // R center
        assertTrue(threeBlock.isPieceVisible(centersIndex, 0, Vec4i(0, 0, 0, 1))) // O center
        // The O-ring ridges only arrive at the "cross" filter.
        assertFalse(threeBlock.isPieceVisible(centersIndex, 0, Vec4i(0, 1, 0, 1))) // O-U ridge, not yet
        assertTrue(threeBlock.isPieceVisible(crossIndex, 0, Vec4i(0, 1, 0, 1))) // O-U ridge
        assertTrue(threeBlock.isPieceVisible(crossIndex, 0, Vec4i(0, 0, -1, 1))) // O-B ridge
        assertTrue(threeBlock.isPieceVisible(crossIndex, 0, Vec4i(1, 0, 0, 0))) // R center still visible (carried)
        // The O-L and O-R ridges are deliberately left out (4-Cross leaves L/R unsolved).
        assertFalse(threeBlock.isPieceVisible(crossIndex, 0, Vec4i(-1, 0, 0, 1))) // O-L ridge
        assertFalse(threeBlock.isPieceVisible(crossIndex, 0, Vec4i(1, 0, 0, 1))) // O-R ridge
        // A non-O ridge shouldn't be swept in just for touching a named cell (e.g. U-F).
        assertFalse(threeBlock.isPieceVisible(crossIndex, 0, Vec4i(0, 1, 1, 0)))
    }

    @Test
    fun `the corner-block typo is fixed -- last cfop line matches its siblings' pattern`() {
        // Every corner-block line is "I<3 letters><Reps>c,<same 3 letters><Reps>e" -- the source
        // text David provided had "IDFRc,DFre" here (lowercase r, missing e-vs-c letter symmetry)
        // where every sibling line (e.g. "IUFRc,URFe") does not. Now the last subfilter of cfop's
        // "f2l-b yellow" filter -- the corner-block stages were later split (1638bb5) out of the
        // original single 24-subfilter "cfop" filter.
        val f2lBYellow = PieceFilters.BUILTIN_FILTER_SETS[1].filters.first { it.name == "f2l-b yellow" }
        assertEquals("+IDFRc,DFRe", f2lBYellow.subfilters.last())
    }

    // --- Token grammar: intersection (concatenated letters), union (commas), type letters ------

    @Test
    fun `a cell+type token matches only the piece at that exact intersection and type`() {
        val filter = PieceFilter("test", listOf("UFr"))
        // U is +Y, F is +Z; a ridge (2 stickers) touching both has x=0, y=1, z=1, w=0.
        assertTrue(filter.isPieceVisible(0, Vec4i(0, 1, 1, 0)))
        // Same U/F intersection, but a corner (3 nonzero coords) -- type letter must disqualify it.
        assertFalse(filter.isPieceVisible(0, Vec4i(1, 1, 1, 0)))
        // Right sticker count, wrong cells (touches U but not F).
        assertFalse(filter.isPieceVisible(0, Vec4i(0, 1, -1, 0)))
    }

    @Test
    fun `a bare type letter matches every piece of that type regardless of cell`() {
        val filter = PieceFilter("test", listOf("m"))
        assertTrue(filter.isPieceVisible(0, Vec4i(1, 0, 0, 0))) // R center
        assertTrue(filter.isPieceVisible(0, Vec4i(0, 0, 0, -1))) // I center
        assertFalse(filter.isPieceVisible(0, Vec4i(1, 1, 0, 0))) // a ridge, not a center
    }

    @Test
    fun `a bare cell letter matches every piece touching that cell regardless of type`() {
        val filter = PieceFilter("test", listOf("O")) // O is +W
        assertTrue(filter.isPieceVisible(0, Vec4i(0, 0, 0, 1))) // O center
        assertTrue(filter.isPieceVisible(0, Vec4i(1, 1, 1, 1))) // a corner touching O among others
        assertFalse(filter.isPieceVisible(0, Vec4i(0, 0, 0, -1))) // I center, not O
    }

    @Test
    fun `comma-separated tokens within one subfilter are unioned`() {
        val filter = PieceFilter("test", listOf("IUFe,UFr"))
        val theEdge = Vec4i(0, 1, 1, -1) // touches I, U, F -- 3 stickers
        val theRidge = Vec4i(0, 1, 1, 0) // touches U, F -- 2 stickers
        val neither = Vec4i(0, -1, 1, 0) // touches D, F instead of U, F
        assertTrue(filter.isPieceVisible(0, theEdge))
        assertTrue(filter.isPieceVisible(0, theRidge))
        assertFalse(filter.isPieceVisible(0, neither))
    }

    // --- Bare same-category runs are a union, not an (always-empty) intersection ----------------

    @Test
    fun `a bare run of only cell letters is a union of those cells`() {
        val filter = PieceFilter("test", listOf("LR"))
        assertTrue(filter.isPieceVisible(0, Vec4i(-1, 0, 0, 0))) // touches L only
        assertTrue(filter.isPieceVisible(0, Vec4i(1, 0, 0, 0))) // touches R only
        assertFalse(filter.isPieceVisible(0, Vec4i(0, 1, 0, 0))) // touches neither (U center)
    }

    @Test
    fun `a bare run of only type letters is a union of those types`() {
        val filter = PieceFilter("test", listOf("mre"))
        assertTrue(filter.isPieceVisible(0, Vec4i(1, 0, 0, 0))) // center (1 sticker)
        assertTrue(filter.isPieceVisible(0, Vec4i(1, 1, 0, 0))) // ridge (2 stickers)
        assertTrue(filter.isPieceVisible(0, Vec4i(1, 1, 1, 0))) // edge (3 stickers)
        assertFalse(filter.isPieceVisible(0, Vec4i(1, 1, 1, 1))) // corner (4 stickers)
    }

    // --- "-" subtracts a group from the positive group, chainable -------------------------------

    @Test
    fun `a dash removes pieces matching the following group from the positive group`() {
        val filter = PieceFilter("test", listOf("mre-LR"))
        val lCenter = Vec4i(-1, 0, 0, 0) // center, touches L -- excluded by "-LR"
        val rRidge = Vec4i(1, 1, 0, 0) // ridge, touches R -- excluded by "-LR"
        val uCenter = Vec4i(0, 1, 0, 0) // center, touches neither L nor R -- stays
        val corner = Vec4i(1, 1, 1, 1) // corner -- excluded by "mre" (wrong type); every corner
        // also necessarily touches L or R (x is always +-1), so this doubles as another "-LR" case
        assertFalse(filter.isPieceVisible(0, lCenter))
        assertFalse(filter.isPieceVisible(0, rRidge))
        assertTrue(filter.isPieceVisible(0, uCenter))
        assertFalse(filter.isPieceVisible(0, corner))
    }

    @Test
    fun `chained dashes remove the same thing as one unioned group`() {
        val chained = PieceFilter("test", listOf("mre-L-R"))
        val unioned = PieceFilter("test", listOf("mre-LR"))
        val pieces = listOf(
            Vec4i(-1, 0, 0, 0), // L center
            Vec4i(1, 1, 0, 0), // R ridge
            Vec4i(0, 1, 0, 0), // U center
            Vec4i(1, 1, 1, 1), // corner
        )
        for (piece in pieces) {
            assertEquals(unioned.isPieceVisible(0, piece), chained.isPieceVisible(0, piece))
        }
    }

    @Test
    fun `the original cell-intersection-plus-type-letter shape is unaffected by the dash feature`() {
        // Same case as the cell+type test above, just re-confirmed after the grammar rewrite.
        val filter = PieceFilter("test", listOf("IUFe-O"))
        val theEdge = Vec4i(0, 1, 1, -1) // touches I, U, F -- doesn't touch O, so "-O" is a no-op
        assertTrue(filter.isPieceVisible(0, theEdge))
    }

    @Test
    fun `a group shape that's neither all-cell, all-type, nor cells-plus-trailing-type is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            // 'm' (type) followed by 'L' (cell) -- not all-cell, not all-type, and doesn't end in
            // a type letter, so none of the three supported shapes apply.
            PieceFilter("test", listOf("mL"))
        }
    }

    // --- "+"-controlled stepping within one filter: reset by default, accumulate with "+" ------

    @Test
    fun `a subfilter with no leading + resets, clearing what the previous step showed`() {
        val filter = PieceFilter("test", listOf("m", "r"))
        val center = Vec4i(1, 0, 0, 0)
        val ridge = Vec4i(1, 1, 0, 0)
        assertTrue(filter.isPieceVisible(0, center))
        assertFalse(filter.isPieceVisible(0, ridge))
        // Step 1 ("r", no "+") resets -- the center from step 0 is no longer visible.
        assertFalse(filter.isPieceVisible(1, center))
        assertTrue(filter.isPieceVisible(1, ridge))
    }

    @Test
    fun `a leading + accumulates onto what the previous step showed instead of resetting`() {
        val filter = PieceFilter("test", listOf("m", "+r"))
        val center = Vec4i(1, 0, 0, 0)
        val ridge = Vec4i(1, 1, 0, 0)
        assertTrue(filter.isPieceVisible(0, center))
        assertFalse(filter.isPieceVisible(0, ridge))
        // Step 1 ("+r") accumulates -- the center from step 0 stays visible too.
        assertTrue(filter.isPieceVisible(1, center))
        assertTrue(filter.isPieceVisible(1, ridge))
    }

    @Test
    fun `a reset partway through a run of + subfilters clears everything before it`() {
        val filter = PieceFilter("test", listOf("m", "+r", "e", "+c"))
        val center = Vec4i(1, 0, 0, 0)
        val ridge = Vec4i(1, 1, 0, 0)
        val edge = Vec4i(1, 1, 1, 0)
        val corner = Vec4i(1, 1, 1, 1)
        // Step 2 ("e", no "+") resets, dropping the accumulated m+r from steps 0-1.
        assertFalse(filter.isPieceVisible(2, center))
        assertFalse(filter.isPieceVisible(2, ridge))
        assertTrue(filter.isPieceVisible(2, edge))
        // Step 3 ("+c") accumulates back onto step 2's reset window (edge), not steps 0-1.
        assertFalse(filter.isPieceVisible(3, center))
        assertFalse(filter.isPieceVisible(3, ridge))
        assertTrue(filter.isPieceVisible(3, edge))
        assertTrue(filter.isPieceVisible(3, corner))
    }

    @Test
    fun `step is clamped into range rather than throwing`() {
        val filter = PieceFilter("test", listOf("m", "r"))
        val ridge = Vec4i(1, 1, 0, 0)
        assertFalse(filter.isPieceVisible(-1, ridge)) // clamps to 0, ridge not visible yet
        assertTrue(filter.isPieceVisible(99, ridge)) // clamps to the last subfilter
    }

    // --- "+" also controls whether crossing into a *new* filter clears prior pieces ------------

    @Test
    fun `crossing into a new filter resets by default, same as within a filter`() {
        val set = PieceFilterSet("set", listOf(PieceFilter("a", listOf("m")), PieceFilter("b", listOf("r"))))
        val center = Vec4i(1, 0, 0, 0)
        val ridge = Vec4i(1, 1, 0, 0)
        assertTrue(set.isPieceVisible(0, 0, center))
        // Filter b's first subfilter has no "+" -- crossing into it clears filter a's pieces.
        assertFalse(set.isPieceVisible(1, 0, center))
        assertTrue(set.isPieceVisible(1, 0, ridge))
    }

    @Test
    fun `a + on a new filter's first subfilter carries the previous filter's pieces forward`() {
        val set = PieceFilterSet("set", listOf(PieceFilter("a", listOf("m")), PieceFilter("b", listOf("+r"))))
        val center = Vec4i(1, 0, 0, 0)
        val ridge = Vec4i(1, 1, 0, 0)
        assertTrue(set.isPieceVisible(0, 0, center))
        // Filter b's first subfilter is "+r" -- filter a's center stays visible alongside it,
        // even though the on-screen *label* has already moved from "a" to "b".
        assertTrue(set.isPieceVisible(1, 0, center))
        assertTrue(set.isPieceVisible(1, 0, ridge))
    }

    @Test
    fun `+ carry-in chains across multiple consecutive all-additive filters`() {
        val set = PieceFilterSet(
            "set",
            listOf(
                PieceFilter("a", listOf("m")),
                PieceFilter("b", listOf("+r")),
                PieceFilter("c", listOf("+e")),
            ),
        )
        val center = Vec4i(1, 0, 0, 0)
        val ridge = Vec4i(1, 1, 0, 0)
        val edge = Vec4i(1, 1, 1, 0)
        // Filter c's "+e" reaches back through filter b's "+r" all the way to filter a's "m".
        assertTrue(set.isPieceVisible(2, 0, center))
        assertTrue(set.isPieceVisible(2, 0, ridge))
        assertTrue(set.isPieceVisible(2, 0, edge))
    }

    @Test
    fun `a leading + on a filter-set's very first subfilter is a harmless no-op`() {
        val set = PieceFilterSet("set", listOf(PieceFilter("a", listOf("+m"))))
        assertTrue(set.isPieceVisible(0, 0, Vec4i(1, 0, 0, 0)))
        assertFalse(set.isPieceVisible(0, 0, Vec4i(1, 1, 0, 0)))
    }

    // --- Parsing a multi-filter filter-set -------------------------------------------------------

    @Test
    fun `a filter-set can contain multiple filters, each with their own subfilters`() {
        val filterSets = PieceFilters.parseFilterText(
            """
            3block:
              cross:
                - "m,Ir"
              left cross:
                - "IUFe,UFr"
                - "IURe,URr"
            """.trimIndent(),
        )
        assertEquals(1, filterSets.size)
        val set = filterSets[0]
        assertEquals("3block", set.name)
        assertEquals(listOf("cross", "left cross"), set.filters.map { it.name })
        assertEquals(listOf("m,Ir"), set.filters[0].subfilters)
        assertEquals(listOf("IUFe,UFr", "IURe,URr"), set.filters[1].subfilters)
    }

    @Test
    fun `header indent width doesn't matter, only leading-dash distinguishes a subfilter`() {
        // One space for the filter header, four for its subfilter -- deliberately not matching
        // the file's own documented 2/4-space convention, since the format promises any amount
        // of leading whitespace works, not a pinned width.
        val filterSets = PieceFilters.parseFilterText("a:\n b:\n    - \"m\"\n")
        assertEquals(listOf(PieceFilterSet("a", listOf(PieceFilter("b", listOf("m"))))), filterSets)
    }

    // --- Parser error handling -------------------------------------------------------------------

    @Test
    fun `a subfilter line before any filter header is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PieceFilters.parseFilterText("test:\n  - \"m\"\n")
        }
    }

    @Test
    fun `a filter header before any filter-set header is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PieceFilters.parseFilterText("  test:\n    - \"m\"\n")
        }
    }

    @Test
    fun `an empty filter (header with no subfilters) is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PieceFilters.parseFilterText("a:\n  b:\n  c:\n    - \"m\"\n")
        }
    }

    @Test
    fun `an empty filter-set (header with no filters) is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PieceFilters.parseFilterText("a:\nb:\n  b:\n    - \"m\"\n")
        }
    }

    @Test
    fun `text with no filter sets at all is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PieceFilters.parseFilterText("# just a comment\n")
        }
    }

    @Test
    fun `an unknown cell letter is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PieceFilters.parseFilterText("test:\n  test:\n    - \"Q\"\n")
        }
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val filterSets = PieceFilters.parseFilterText(
            """
            # a leading comment
            test:
              test:
                - "m" # trailing comment on a subfilter line

                - "r"
            """.trimIndent(),
        )
        assertEquals(listOf(PieceFilterSet("test", listOf(PieceFilter("test", listOf("m", "r"))))), filterSets)
    }
}
