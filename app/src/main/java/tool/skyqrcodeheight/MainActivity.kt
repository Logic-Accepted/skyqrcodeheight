// MainActivity.kt
package tool.skyqrcodeheight

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch
import java.io.InputStream
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.graphics.get
import androidx.core.graphics.scale

class MainActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var resultView: TextView
    private var selectedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        resultView = findViewById(R.id.resultView)

        val selectButton = findViewById<Button>(R.id.selectButton)
        val scanButton = findViewById<Button>(R.id.scanButton)

        val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let {
                    val inputStream: InputStream? = contentResolver.openInputStream(it)
                    selectedBitmap = BitmapFactory.decodeStream(inputStream)
                    imageView.setImageBitmap(selectedBitmap)
                }
            }
        }

        selectButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        scanButton.setOnClickListener {
            selectedBitmap?.let {
                val centerCropped = cropCenter(it, 1.0f)
                val inverted = invertColors(centerCropped)
                val enhanced = enhanceImage(inverted)
                imageView.setImageBitmap(enhanced)

                lifecycleScope.launch {
                    val rawValue = scanWithMultipleScales(enhanced)
                    if (rawValue != null) {
                        try {
                            val decoded = String(Base64.decode(rawValue, Base64.DEFAULT))

                            val heightRegex = Regex("eight[^:=\\d\\-.]{0,5}[:=]\\s*(-?\\d+(\\.\\d+)?)")
                            val scaleRegex = Regex("cale[^:=\\d\\-.]{0,5}[:=]\\s*(-?\\d+(\\.\\d+)?)")

                            val heightMatch = heightRegex.find(decoded)
                            val scaleMatch = scaleRegex.find(decoded)

                            val height = heightMatch?.groups?.get(1)?.value?.toDoubleOrNull()
                            val scale = scaleMatch?.groups?.get(1)?.value?.toDoubleOrNull()

                            val finalHeight = if (height != null && scale != null) {
                                42.7509 - ((-0.0652 * height * height + 3.0729 * height + 35.4599) * (0.126 * scale + 0.7) / 0.7)
                            } else null

                            val resultText = buildString {
                                append("解码内容：\n$decoded")
                                if (height != null) append("\nheight: $height")
                                if (scale != null) append("\nscale: $scale")
                                if (finalHeight != null) append("\n\n你的身高为: %.4f".format(finalHeight))
                            }
                            resultView.text = resultText
                        } catch (e: Exception) {
                            resultView.text = "扫码成功，但Base64解码失败：\n$rawValue"
                        }
                    } else {
                        resultView.text = "多次缩放后仍未识别二维码"
                    }
                }
            } ?: run {
                resultView.text = "请先选择一张图片"
            }
        }
    }

    private fun cropCenter(bitmap: Bitmap, ratio: Float = 0.67f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val size = (minOf(width, height) * ratio).toInt()
        val left = (width - size) / 2
        val top = (height - size) / 2
        return Bitmap.createBitmap(bitmap, left, top, size, size)
    }

    private fun invertColors(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val inverted = createBitmap(width, height, config)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap[x, y]
                val r = 255 - Color.red(pixel)
                val g = 255 - Color.green(pixel)
                val b = 255 - Color.blue(pixel)
                val a = Color.alpha(pixel)
                inverted[x, y] = Color.argb(a, r, g, b)
            }
        }
        return inverted
    }

    private suspend fun scanWithMultipleScales(bitmap: Bitmap): String? {
        val scanner = BarcodeScanning.getClient()
        val scales = listOf(0.9f, 1.0f, 1.1f, 1.2f)

        for (scale in scales) {
            val resized =
                bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
            val image = InputImage.fromBitmap(resized, 0)
            try {
                val barcodes = scanner.process(image).await()
                val barcode = barcodes.firstOrNull()
                if (barcode != null) return barcode.rawValue
            } catch (_: Exception) {

            }
        }
        return null
    }

    private fun enhanceImage(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val gray = IntArray(width * height)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val grayVal = (0.3 * r + 0.59 * g + 0.11 * b).toInt()
            gray[i] = grayVal
        }

        val minGray = gray.minOrNull() ?: 0
        val maxGray = gray.maxOrNull() ?: 255
        val contrastStretch = 255f / (maxGray - minGray).coerceAtLeast(1)

        val stretched = IntArray(width * height)
        for (i in gray.indices) {
            val v = ((gray[i] - minGray) * contrastStretch).toInt().coerceIn(0, 255)
            stretched[i] = v
        }

        val kernel = arrayOf(
            intArrayOf(0, -1, 0),
            intArrayOf(-1, 5, -1),
            intArrayOf(0, -1, 0)
        )

        val result = createBitmap(width, height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                for (ky in 0..2) {
                    for (kx in 0..2) {
                        val px = x + kx - 1
                        val py = y + ky - 1
                        sum += stretched[py * width + px] * kernel[ky][kx]
                    }
                }
                val v = sum.coerceIn(0, 255)
                val a = Color.alpha(pixels[y * width + x])
                result[x, y] = Color.argb(a, v, v, v)
            }
        }

        for (x in 0 until width) {
            result[x, 0] = src[x, 0]
            result[x, height - 1] = src[x, height - 1]
        }
        for (y in 0 until height) {
            result[0, y] = src[0, y]
            result[width - 1, y] = src[width - 1, y]
        }

        return result
    }

}
