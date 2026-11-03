package com.samosaai.masala.tools

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Markdown path of `webfetch`: structure kept, scripts and chrome dropped. */
class WebFetchMarkdownTest {
    private fun md(html: String) =
        WebFetchTool().htmlToMarkdown(Jsoup.parse(html, "https://example.com/page"))

    @Test fun `headings links and emphasis survive`() {
        val out = md(
            "<h1>Title</h1><p>Hello <strong>big</strong> and <em>small</em> " +
                "<a href=\"/docs\">docs</a>.</p>",
        )
        assertTrue("# Title" in out)
        assertTrue("**big**" in out)
        assertTrue("*small*" in out)
        assertTrue("[docs](https://example.com/docs)" in out)
    }

    @Test fun `lists code blocks and quotes survive`() {
        val out = md(
            "<ul><li>one</li><li>two</li></ul>" +
                "<pre>run()</pre>" +
                "<blockquote>note</blockquote>",
        )
        assertTrue("- one" in out)
        assertTrue("- two" in out)
        assertTrue("```\nrun()\n```" in out)
        assertTrue("> note" in out)
    }

    @Test fun `images keep their alt text and source`() {
        val out = md("<p><img src=\"/i.png\" alt=\"a diagram\"></p>")
        assertTrue("[a diagram](https://example.com/i.png)" in out)
    }

    @Test fun `tables render as text`() {
        val out = md("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>")
        assertTrue("A" in out && "B" in out && "1" in out && "2" in out)
    }

    @Test fun `inline tags do not leak markup`() {
        val out = md("<div><span>plain</span><section>text</section></div>")
        assertTrue("plain" in out && "text" in out)
        assertTrue("<div>" !in out && "<span>" !in out)
    }

    @Test fun `empty pages stay empty`() {
        assertFalse(md("<html><body></body></html>").isNotBlank())
    }
}
