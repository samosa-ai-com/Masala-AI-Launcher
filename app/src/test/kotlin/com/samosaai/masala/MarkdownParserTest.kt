package com.samosaai.masala

import com.samosaai.masala.ui.components.MarkdownParser
import com.samosaai.masala.ui.components.MarkdownSegment
import com.samosaai.masala.ui.components.TableAlign
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownParserTest {
    @Test fun fencedBlocksAreSplitOut() {
        val md = "Run this:\n\n```sh\nls -la\n```\n\nThen done."
        assertEquals(
            listOf(MarkdownSegment.Prose("Run this:"), MarkdownSegment.Code("ls -la", "sh"), MarkdownSegment.Prose("Then done.")),
            MarkdownParser.segments(md),
        )
    }

    @Test fun unclosedFenceRunsToTheEnd() {
        assertEquals(listOf(MarkdownSegment.Code("a\nb", null)), MarkdownParser.segments("```\na\nb"))
    }

    @Test fun longerFenceIsNotClosedByShorterOne() {
        assertEquals(listOf(MarkdownSegment.Code("```\ninner\n```", null)), MarkdownParser.segments("````\n```\ninner\n```\n````"))
    }

    @Test fun aTableBecomesHeadersAlignsAndRows() {
        val md = "Pick one:\n\n| Name | Price |\n| :--- | ---: |\n| Tea | 20 |\n| Coffee | 30 |"
        assertEquals(
            listOf(
                MarkdownSegment.Prose("Pick one:"),
                MarkdownSegment.Table(
                    headers = listOf("Name", "Price"),
                    aligns = listOf(TableAlign.LEFT, TableAlign.RIGHT),
                    rows = listOf(listOf("Tea", "20"), listOf("Coffee", "30")),
                ),
            ),
            MarkdownParser.splitTables(md),
        )
    }

    @Test fun escapedPipesStayInsideTheirCell() {
        val md = "| A | B |\n| --- | --- |\n| x \\| y | z |"
        val table = MarkdownParser.splitTables(md).single() as MarkdownSegment.Table
        assertEquals(listOf("x \\| y", "z"), table.rows.single())
    }

    @Test fun textWithoutATableStaysProse() {
        val md = "Just | text\nwith no delimiter row."
        assertEquals(listOf(MarkdownSegment.Prose(md)), MarkdownParser.splitTables(md))
    }
}
