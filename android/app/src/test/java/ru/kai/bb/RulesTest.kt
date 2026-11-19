package ru.kai.bb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRulesTest {

    @Test
    fun zoomIsClampedToSupportedRange() {
        assertEquals(80, SiteRules.clampZoom(10))
        assertEquals(160, SiteRules.clampZoom(400))
        assertEquals(120, SiteRules.clampZoom(120))
    }

    @Test
    fun desktopModeSwitchesUserAgent() {
        val ua = SiteRules.userAgent(true, "Mobile-UA")
        assertTrue(ua.contains("Windows NT"))
        assertEquals("Mobile-UA", SiteRules.userAgent(false, "Mobile-UA"))
    }

    @Test
    fun startUrlKeepsOnlyOwnPortal() {
        assertEquals(Site.ENTRY_URL, SiteRules.startUrl(null))
        assertEquals(
            "https://bb.kai.ru/webapps/portal/execute/tabs/tabAction",
            SiteRules.startUrl("https://bb.kai.ru/webapps/portal/execute/tabs/tabAction"),
        )
        // чужая страница в last_url (например, после «Открыть в браузере») не должна подменять вход
        assertEquals(Site.ENTRY_URL, SiteRules.startUrl("https://example.com/x"))
    }

    @Test
    fun hostCheckAcceptsSubdomainsOnly() {
        assertTrue(Site.isOwnHost("https://bb.kai.ru/ultra"))
        assertTrue(Site.isOwnHost("https://files.bb.kai.ru/a.pdf"))
        assertTrue(!Site.isOwnHost("https://notbb.kai.ru/x"))
        assertTrue(!Site.isOwnHost("https://kairu.com/x"))
        assertTrue(!Site.isOwnHost(null))
    }
}

class DownloadNamesTest {

    @Test
    fun takesRfc5987FileName() {
        val cd = "attachment; filename*=UTF-8''%D0%9B%D0%B5%D0%BA%D1%86%D0%B8%D1%8F%201.pdf"
        assertEquals("Лекция 1.pdf", DownloadNames.guess("https://bb.kai.ru/x?id=1", cd, "application/pdf"))
    }

    @Test
    fun takesQuotedFileName() {
        val cd = "attachment; filename=\"Отчёт по практике.docx\""
        assertEquals("Отчёт по практике.docx", DownloadNames.guess("https://bb.kai.ru/y", cd, null))
    }

    @Test
    fun fallsBackToUrlAndAddsExtension() {
        val name = DownloadNames.guess("https://bb.kai.ru/bbcswebdav/xid-3643344_1", null, "application/pdf")
        assertEquals("xid-3643344_1.pdf", name)
    }

    @Test
    fun decodesPercentEncodedUrlName() {
        val name = DownloadNames.guess(
            "https://bb.kai.ru/files/%D0%A0%D1%83%D0%BA%D0%BE%D0%B2%D0%BE%D0%B4%D1%81%D1%82%D0%B2%D0%BE.pdf",
            null,
            null,
        )
        assertEquals("Руководство.pdf", name)
    }

    @Test
    fun stripsUnsafeCharacters() {
        val name = DownloadNames.guess("https://bb.kai.ru/a", "attachment; filename=\"a/b:c*?.pdf\"", "application/pdf")
        assertEquals("a_b_c__.pdf", name)
    }

    @Test
    fun keepsNameWithoutDuplicatingExtension() {
        assertEquals("lecture.pdf", DownloadNames.guess("https://bb.kai.ru/lecture.pdf", null, "application/pdf"))
    }
}
