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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.InputStream
import java.util.regex.Pattern

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
                val centerCropped = cropCenter(it, 0.9f)
                val inverted = invertColors(centerCropped)
                imageView.setImageBitmap(inverted)
                scanQRCode(inverted)
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
        val inverted = Bitmap.createBitmap(width, height, config)

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap.getPixel(x, y)
                val r = 255 - Color.red(pixel)
                val g = 255 - Color.green(pixel)
                val b = 255 - Color.blue(pixel)
                val a = Color.alpha(pixel)
                inverted.setPixel(x, y, Color.argb(a, r, g, b))
            }
        }
        return inverted
    }

    private fun scanQRCode(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isEmpty()) {
                    resultView.text = "未能识别二维码"
                    return@addOnSuccessListener
                }
                val rawValue = barcodes[0].rawValue ?: ""
                try {
                    val decoded = String(Base64.decode(rawValue, Base64.DEFAULT))

                    val heightRegex = Pattern.compile("\"?height\"?\\s*[:=]\\s*(-?\\d+(?:\\.\\d+)?)")
                    val scaleRegex = Pattern.compile("\"?scale\"?\\s*[:=]\\s*(\\d+(?:\\.\\d+)?)")

                    val heightMatch = heightRegex.matcher(decoded)
                    val scaleMatch = scaleRegex.matcher(decoded)

                    val height = if (heightMatch.find()) heightMatch.group(1)?.toDoubleOrNull() else null
                    val scale = if (scaleMatch.find()) scaleMatch.group(1)?.toDoubleOrNull() else null

                    val finalHeight = if (height != null && scale != null) {
                        42.7509 - ((-0.0652 * height * height + 3.0729 * height + 35.4599) * (0.126 * scale + 0.7) / 0.7)
                    } else {
                        null
                    }

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
            }
            .addOnFailureListener {
                resultView.text = "识别失败：${it.message}"
            }
    }
}
