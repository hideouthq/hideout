package gg.hideouthq.core.data.live

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {
    @Test
    fun breaksParagraphsAndListsSurviveAsText() {
        val html = "Victor Bride skin for Lady Geist<br><br>Model by <a href=\"https://x.com/Nobel_via\">Nobel</a>" +
            "<p>Features</p><ul><li>New model</li><li>Custom icons</li></ul>"
        assertEquals("Victor Bride skin for Lady Geist\n\nModel by Nobel\nFeatures\n\n• New model\n• Custom icons", HtmlText.toPlainText(html))
    }

    @Test
    fun entitiesAreDecodedAndSpacingTidied() {
        assertEquals("Mo & Krill’s \"best\" skin — v2 😀", HtmlText.toPlainText("Mo &amp; Krill&#8217;s &quot;best&quot;   skin &mdash; v2 &#x1F600;"))
        assertEquals("a\n\nb", HtmlText.toPlainText("<div>a</div>\n\n\n<div>b</div>"))
        assertEquals("&unknown; stays", HtmlText.decodeEntities("&unknown; stays"))
    }
}
