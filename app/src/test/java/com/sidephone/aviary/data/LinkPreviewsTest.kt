package com.sidephone.aviary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card metadata is scraped with regexes over real-world HTML, which varies far more than the
 * spec suggests: attribute order flips, quoting flips, and plenty of pages have no card at all.
 * These cover the shapes that actually show up.
 */
class LinkPreviewsTest {

    @Test
    fun `finds the first http url in a sentence`() {
        assertEquals(
            "https://example.com/story",
            LinkPreviews.firstUrl("have a look at https://example.com/story it's good"),
        )
    }

    @Test
    fun `trims sentence punctuation off the end`() {
        assertEquals(
            "https://example.com/a",
            LinkPreviews.firstUrl("read https://example.com/a."),
        )
        assertEquals(
            "https://example.com/b",
            LinkPreviews.firstUrl("(see https://example.com/b)"),
        )
    }

    @Test
    fun `ignores text that only looks like a link`() {
        // No scheme, so no request: these are ordinary words, a file path and a version.
        assertNull(LinkPreviews.firstUrl("go to example.com later"))
        assertNull(LinkPreviews.firstUrl("open app/src/main/AndroidManifest.xml"))
        assertNull(LinkPreviews.firstUrl("upgraded to 2.14.1 today"))
    }

    @Test
    fun `reads standard opengraph tags`() {
        val html = """
            <html><head>
            <meta property="og:site_name" content="Example News">
            <meta property="og:title" content="A headline">
            <meta property="og:description" content="What the story says.">
            <meta property="og:image" content="https://cdn.example.com/a.jpg">
            </head><body>ignored</body></html>
        """.trimIndent()
        val p = LinkPreviews.parseHtml(html, "https://example.com/story")
        assertEquals("A headline", p.title)
        assertEquals("What the story says.", p.description)
        assertEquals("https://cdn.example.com/a.jpg", p.imageUrl)
        assertEquals("Example News", p.siteName)
    }

    @Test
    fun `reads tags with content before property and single quotes`() {
        val html = """
            <head><meta content='Reversed title' property='og:title'/>
            <meta content='Reversed body' name='twitter:description'/></head>
        """.trimIndent()
        val p = LinkPreviews.parseHtml(html, "https://example.com/x")
        assertEquals("Reversed title", p.title)
        assertEquals("Reversed body", p.description)
    }

    @Test
    fun `falls back to the title tag and the host`() {
        val html = "<html><head><title>Just a title</title></head><body></body></html>"
        val p = LinkPreviews.parseHtml(html, "https://www.example.com/x")
        assertEquals("Just a title", p.title)
        // og:site_name absent, so the bare host stands in — without the www.
        assertEquals("example.com", p.siteName)
    }

    @Test
    fun `resolves a relative image against the page`() {
        val html = """<head><meta property="og:image" content="/img/cover.png"></head>"""
        val p = LinkPreviews.parseHtml(html, "https://example.com/deep/story?x=1")
        assertEquals("https://example.com/img/cover.png", p.imageUrl)
    }

    @Test
    fun `unescapes entities in metadata`() {
        val html = """<head><meta property="og:title" content="Tom &amp; Jerry&#39;s &quot;day&quot;"></head>"""
        assertEquals("""Tom & Jerry's "day"""", LinkPreviews.parseHtml(html, "https://example.com").title)
    }

    @Test
    fun `a page with no card reports empty`() {
        val p = LinkPreviews.parseHtml("<html><body>nothing here</body></html>", "https://example.com/x")
        assertNull(p.title)
        assertNull(p.description)
        assertNull(p.imageUrl)
        // siteName alone isn't worth a card.
        assertTrue(p.isEmpty)
    }

    @Test
    fun `body metadata does not leak into the card`() {
        // Only <head> is scanned, so an article quoting a meta tag can't rewrite the preview.
        val html = """
            <head><meta property="og:title" content="Real title"></head>
            <body><meta property="og:title" content="Injected"></body>
        """.trimIndent()
        assertEquals("Real title", LinkPreviews.parseHtml(html, "https://example.com").title)
    }
}
