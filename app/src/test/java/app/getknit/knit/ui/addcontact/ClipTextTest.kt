package app.getknit.knit.ui.addcontact

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Add contact screen's Paste: a clip whose item is a URI (a copied image or file) is read through the
 * content resolver, so it must not crash and must not stop a later text item from being found. 2.8.0
 * passed a null context and crashed in `ClipData.Item.coerceToText` on Android 12/13. Pinned to API 32:
 * newer platforms catch the null context's NPE inside `coerceToText`, so SDK 36 cannot reproduce it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ClipTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aContentUriItemDoesNotCrashAndTheTextItemAfterItIsFound() {
        val clip =
            ClipData(
                ClipDescription("copied", arrayOf("image/png", ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item(Uri.parse("content://media/external/images/media/42")),
            )
        clip.addItem(ClipData.Item("https://getknit.app/c#abc"))

        assertEquals("https://getknit.app/c#abc", clipText(clip, context))
    }

    @Test
    fun aClipHoldingOnlyAnUnreadableUriHasNoText() {
        val clip = ClipData.newRawUri("copied", Uri.parse("content://media/external/images/media/42"))

        assertNull(clipText(clip, context))
    }
}
