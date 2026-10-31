package ru.eluno.feature.commissioning

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MatterQrDecoderTest {
    private val payload = "MT:Y.K9042C00KA0648G00"

    private fun scan(source: LuminanceSource): String? {
        val decoder = matterQrDecoderFactory().createDecoder(emptyMap<DecodeHintType, Any>())
        repeat(6) { decoder.decode(source)?.let { return it.text } }
        return null
    }

    @Test fun `ordinary and inverted QR remain readable`() {
        assertEquals(payload, scan(frame(scale = 6, inverted = false, darkBackground = false)))
        assertEquals(payload, scan(frame(scale = 6, inverted = true, darkBackground = true)))
    }

    @Test fun `screen QR without quiet zone decodes at different sizes and positions`() {
        for (scale in listOf(4, 6, 8)) for (offset in listOf(-50, 0, 50)) {
            assertEquals(payload, scan(frame(scale, false, true, offset)))
        }
    }

    @Test fun `screen without QR is not turned into a result`() {
        val pixels = ByteArray(320 * 480) { 20 }
        assertNull(scan(PlanarYUVLuminanceSource(pixels, 320, 480, 0, 0, 320, 480, false)))
    }

    @Test fun `screen QR remains readable with a small camera tilt`() {
        val source = frame(8, false, true)
        val original = java.awt.image.BufferedImage(source.width, source.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val luminance = source.matrix
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val v = luminance[y * source.width + x].toInt() and 255
            original.setRGB(x, y, (v shl 16) or (v shl 8) or v)
        }
        for (angle in listOf(-5.0, 5.0)) {
            val rotated = java.awt.image.BufferedImage(source.width, source.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = rotated.createGraphics()
            graphics.rotate(Math.toRadians(angle), source.width / 2.0, source.height / 2.0)
            graphics.drawImage(original, 0, 0, null)
            graphics.dispose()
            assertEquals(payload, scan(RGBLuminanceSource(rotated.width, rotated.height, rotated.getRGB(0, 0, rotated.width, rotated.height, null, 0, rotated.width))))
        }
    }

    @Test fun `supplied screenshot reproduces old failure and passes production decoder`() {
        // Optional local regression input: never copy a user's setup code/image into test fixtures or logs.
        val path = System.getenv("ELUNO_QR_TEST_IMAGE")
        assumeTrue(path != null)
        val image = ImageIO.read(File(requireNotNull(path)))
        val source = RGBLuminanceSource(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
        val oldDecoder = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)).createDecoder(emptyMap<DecodeHintType, Any>())
        assertNull("Original decoder should reproduce this screenshot's failure", oldDecoder.decode(source))
        val decoded = scan(source)
        assertTrue("Production decoder must recognize a Matter payload", decoded?.startsWith("MT:") == true)
        assertNotNull(MatterQrResultGate().accept(decoded))
    }

    private fun frame(scale: Int, inverted: Boolean, darkBackground: Boolean, offset: Int = 0): LuminanceSource {
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val width = 400
        val height = 600
        val dark = 20.toByte()
        val light = 225.toByte()
        val pixels = ByteArray(width * height) { if (darkBackground) dark else light }
        val left = (width - qr.width * scale) / 2
        val top = (height - qr.height * scale) / 2 + offset
        for (y in 0 until qr.height * scale) for (x in 0 until qr.width * scale) {
            pixels[(top + y) * width + left + x] = if (qr[x / scale, y / scale] xor inverted) dark else light
        }
        // Disconnected UI text-like strokes must not be included in the symbol bounds.
        if (darkBackground) for (y in 40..50) for (x in 40..360) if (x % 12 < 7) pixels[y * width + x] = light
        return PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
    }
}
