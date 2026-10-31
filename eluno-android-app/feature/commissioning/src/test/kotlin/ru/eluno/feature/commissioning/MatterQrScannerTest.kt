package ru.eluno.feature.commissioning

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class MatterQrScannerTest {
    @Test fun `decodes QR pixels and preserves Matter punctuation`() {
        val payload = "MT:Y.K9042C00KA0648G00"
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        val decoded = QRCodeReader().decode(bitmap).text
        assertEquals(payload, MatterQrResultGate().accept(decoded))
    }

    @Test fun `unrelated and empty QR do not prevent later Matter scan`() {
        val gate = MatterQrResultGate()
        listOf(null, "", "MT:", "https://example.com", "12345678901").forEach { assertNull(gate.accept(it)) }
        assertEquals("MT:A-.B", gate.accept("  MT:A-.B  "))
    }

    @Test fun `continuous frames deliver only one code`() {
        val gate = MatterQrResultGate()
        assertEquals("MT:A-.B", gate.accept("MT:A-.B"))
        assertNull(gate.accept("MT:A-.B"))
        assertNull(gate.accept("MT:ANOTHER"))
    }
}
