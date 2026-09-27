package nu.staldal.mws

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconPipelineTest {
    @Test fun fetchesCanonicalUrl() {
        var fetched = ""
        val selected = IconPipeline.select(listOf(icon("ignored", IconCandidate.Source.PAGE)
            .copy(url = "https://EXAMPLE.com./icon.png")), IconFetcher {
            fetched = it.url
            png(64, 64)
        })
        assertEquals("https://example.com/icon.png", fetched)
        assertEquals(fetched, selected?.candidate?.url)
    }

    @Test fun rankingHonorsSourceSizeAndOrder() {
        val candidates = listOf(
            icon("page", IconCandidate.Source.PAGE, "any"),
            icon("mask", IconCandidate.Source.MANIFEST, "any", "maskable"),
            icon("large", IconCandidate.Source.MANIFEST, "1024x1024 256x256", "any"),
            icon("exact", IconCandidate.Source.MANIFEST, "256x256 512x512", "any"),
            icon("small", IconCandidate.Source.MANIFEST, "192x192 384x384", "any"),
            icon("scalable", IconCandidate.Source.MANIFEST, "any", "any", 0),
            icon("scalable2", IconCandidate.Source.MANIFEST, "any", "any", 1),
            icon("mono", IconCandidate.Source.MANIFEST, "any", "monochrome"),
            icon("favicon", IconCandidate.Source.FAVICON),
        )
        assertEquals(listOf("scalable", "scalable2", "exact", "large", "small", "mask",
            "page", "favicon"), IconSelection.ranked(candidates).map { it.url.substringAfterLast('/') })
    }

    @Test fun onlyFirst128PageCandidatesAreConsidered() {
        val candidates = (0..128).map { icon("page$it", IconCandidate.Source.PAGE,
            if (it == 128) "any" else "48x48", order = it) }
        assertEquals(128, IconSelection.ranked(candidates).size)
        assertFalse(IconSelection.ranked(candidates).any { it.url.endsWith("page128") })
    }

    @Test fun failedCandidatesFallThroughInRankOrder() {
        val attempts = mutableListOf<String>()
        val selected = IconPipeline.select(listOf(icon("bad", IconCandidate.Source.MANIFEST,
            "512x512"), icon("good", IconCandidate.Source.PAGE)), IconFetcher {
            attempts += it.url.substringAfterLast('/')
            if (it.url.endsWith("good")) png(64, 64) else byteArrayOf(1, 2)
        })
        assertEquals(listOf("bad", "good"), attempts)
        assertEquals("good", selected?.candidate?.url?.substringAfterLast('/'))
    }

    @Test fun decoderRejectsEncodedAndDecodedLimits() {
        assertNull(IconDecoder.decode(ByteArray(IconDecoder.MAX_ENCODED_BYTES + 1)))
        assertFalse(IconDecoder.dimensionsAllowed(4097, 1))
        assertFalse(IconDecoder.dimensionsAllowed(4096, 4096))
        assertTrue(IconDecoder.dimensionsAllowed(4000, 4000))
        assertNull(IconDecoder.decode(byteArrayOf(1, 2, 3)))
        assertNull(IconDecoder.decode(png(4097, 1)))
    }

    @Test fun svgRejectsActiveAndExternalContent() {
        fun safe(xml: String) = SvgSafety.isSafe(xml.toByteArray())
        assertTrue(safe("<svg xmlns='http://www.w3.org/2000/svg'><circle r='10'/></svg>"))
        assertFalse(safe("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><svg/>"))
        assertFalse(safe("<svg><script>alert(1)</script></svg>"))
        assertFalse(safe("<svg><animate attributeName='r'/></svg>"))
        assertFalse(safe("<svg><image href='https://example.com/a.png'/></svg>"))
        assertFalse(safe("<svg onload='x()'/>") )
        assertFalse(safe("<svg><path fill='url(https://example.com/a)'/></svg>"))
        assertFalse(safe("<svg><text font-family='serif'>hello</text></svg>"))
        assertFalse(safe("<svg><use href='#shape'/></svg>"))
        assertFalse(safe("<svg>" + "<g>".repeat(65) + "</g>".repeat(65) + "</svg>"))
        assertTrue(safe("<svg xmlns='http://www.w3.org/2000/svg'>" +
            "<defs><linearGradient id='g'/></defs><rect fill='url(#g)'/></svg>"))
    }

    @Test fun decodesSafeSvgAndIcoPng() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'>" +
            "<rect width='32' height='32' fill='#ff0000'/></svg>"
        val decodedSvg = IconDecoder.decode(svg.toByteArray(), "image/svg+xml")
        assertNotNull(decodedSvg)
        assertEquals(Color.RED, decodedSvg?.getPixel(1000, 1000))
        assertEquals(Color.RED, decodedSvg?.getPixel(512, 512))
        val payload = png(32, 32)
        val ico = ByteBuffer.allocate(22 + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0)
            putShort(1)
            putShort(1)
            put(32)
            put(32)
            put(0)
            put(0)
            putShort(1)
            putShort(32)
            putInt(payload.size)
            putInt(22)
            put(payload)
        }.array()
        assertEquals(32, IconDecoder.decode(ico)?.width)
    }

    @Test fun renditionsAreSizedTransparentAndMaskableCrop() {
        val source = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        for (size in IconPipeline.SIZES) {
            val output = IconPipeline.render(source, size, false)
            assertEquals(size, output.width)
            assertEquals(size, output.height)
            assertEquals(Color.TRANSPARENT, output.getPixel(size / 2, 0))
            assertEquals(Color.RED, output.getPixel(size / 2, size / 2))
        }
        val maskSource = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        maskSource.eraseColor(Color.RED)
        for (y in 10 until 90) for (x in 10 until 90) maskSource.setPixel(x, y, Color.BLUE)
        val masked = IconPipeline.render(maskSource, 96, true)
        assertEquals(Color.BLUE, masked.getPixel(48, 48))
        assertEquals(Color.BLUE, masked.getPixel(48, 2))
        assertEquals(Color.TRANSPARENT, masked.getPixel(0, 0))
    }

    @Test fun storeUsesNoBackupDirectoryAndReadsPublishedRendition() {
        val context = RuntimeEnvironment.getApplication()
        val store = IconStore(context)
        val selected = SelectedIcon(icon("x", IconCandidate.Source.PAGE),
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) })
        val ref = store.save("app_test", selected)
        assertTrue(java.io.File(context.noBackupFilesDir, "icons/$ref/48.png").isFile)
        assertEquals(48, store.load(ref, 48)?.width)
        assertNotNull(store.defaultIcon())
        store.delete("app_test")
        assertNull(store.load(ref, 48))
    }

    private fun icon(name: String, source: IconCandidate.Source, sizes: String? = null,
        purpose: String? = null, order: Int = 0) =
        IconCandidate("https://example.com/$name", source, sizes, purpose = purpose,
            documentOrder = order)

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()
}
