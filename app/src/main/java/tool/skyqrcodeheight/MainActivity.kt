// MainActivity.kt
package tool.skyqrcodeheight

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var resultView: TextView
    private var selectedBitmap: Bitmap? = null
    private lateinit var loadingSpinner: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        resultView = findViewById(R.id.resultView)
        loadingSpinner = findViewById(R.id.loadingSpinner)

        val selectButton = findViewById<Button>(R.id.selectButton)
        val scanButton = findViewById<Button>(R.id.scanButton)

        val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let {
                    val inputStream: InputStream? = contentResolver.openInputStream(it)
                    selectedBitmap = BitmapFactory.decodeStream(inputStream)
                    imageView.setImageBitmap(selectedBitmap)
                }
            }
        }

        val versionView = findViewById<TextView>(R.id.versionView)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        versionView.text = getString(R.string.version_label, versionName)


        versionView.setOnClickListener {
            showChangelogDialog()
        }


        selectButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        }

        scanButton.setOnClickListener {
            selectedBitmap?.let {
                loadingSpinner.visibility = View.VISIBLE
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1)
                    val rawValue = withContext(Dispatchers.Default) {
                        val inverted = invertColors(it)
                        val enhanced = enhanceImage(inverted)
                        imageView.setImageBitmap(enhanced)
                        scanWithMultipleScales(enhanced)
                    }
                    loadingSpinner.visibility = View.GONE
                    if (rawValue != null) {
                        try {
                            val decoded = String(Base64.decode(rawValue, Base64.DEFAULT))

                            //val scaleRegex = Regex("cale[^:=\\d\\-.eE]{0,5}[:=]\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)")
                            //val heightRegex = Regex("eight[^:=\\d\\-.eE]{0,5}[:=]\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)")

                            val scaleRegex = Regex("cale[^:=\\d\\-.eE]{0,5}[:=]\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)(?![\\w.])")
                            val heightRegex = Regex("eight[^:=\\d\\-.eE]{0,5}[:=]\\s*(-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?)(?![\\w.])")




                            val heightMatch = heightRegex.find(decoded)
                            val scaleMatch = scaleRegex.find(decoded)

                            val height = heightMatch?.groups?.get(1)?.value?.toDoubleOrNull()

                            val scaleStr = scaleMatch?.groups?.get(1)?.value
                            val scale = scaleStr?.toDoubleOrNull()
                            val formattedScale = if (scaleStr?.contains(Regex("[eE]")) == true && scale != null) {
                                formatNormalDecimal(scale)
                            } else {
                                scaleStr ?: "未知"
                            }

                            /*val xjbHeight = if (height != null && scale != null) {
                                42.7508 - ((-0.0652 * height * height + 3.0729 * height + 35.4599) * (0.126 * scale + 0.7) / 0.7)
                            } else null*/

                            val startupZHeight = if (height != null && scale != null) {
                                7.6 - 8.3 * scale - 3 * height
                            } else null

                            val maxHeight = if (scale != null) {
                                7.6 - 8.3 * scale - 3 * 2.0
                            } else null

                            val minHeight = if (scale != null) {
                                7.6 - 8.3 * scale - 3 * -2.0
                            } else null

                            val resultText = buildString {
                                if (startupZHeight != null) append("\n你的身高为: %.4f".format(startupZHeight))
                                // if (xjbHeight != null) append("\n\n按照@小骄宝你的身高为: %.4f".format(xjbHeight))
                                if (maxHeight != null) append("\n最大身高为: %.4f".format(maxHeight))
                                if (minHeight != null) append("\n最小身高为: %.4f".format(minHeight))
                                if (height != null) {
                                    append("\n\nheight: $height")
                                } else {
                                    append("\n\nheight: 数据损坏")
                                }
                                if (scale != null) {
                                    append("\nscale: $formattedScale")
                                } else {
                                    append("\nscale: 数据损坏")
                                }
                                append("\n\n解码内容：$decoded")
                                if (height == null || scale == null) showDataBrokenDialog()
                            }
                            resultView.text = resultText
                        } catch (_: Exception) {
                            resultView.text = "扫码成功但解码失败"
                        }
                    } else {
                        resultView.text = "多次缩放后仍未识别二维码，可以尝试更换手机重新截图二维码"
                    }
                }
            } ?: run {
                resultView.text = "请先选择一张图片"
            }
        }
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
        val mlkitScanner = BarcodeScanning.getClient()
        val scales = listOf(0.9f, 1.0f, 1.1f, 1.2f, 1.5f)

        for (scale in scales) {
            val resized =
                bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
            val image = InputImage.fromBitmap(resized, 0)
            try {
                val barcodes = mlkitScanner.process(image).await()
                val barcode = barcodes.firstOrNull()
                if (barcode != null){
                    return barcode.rawValue
                }
            } catch (_: Exception) { }
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

    fun formatNormalDecimal(value: Double): String {
        val formatter = DecimalFormat("0.################")
        formatter.isGroupingUsed = false
        return formatter.format(value)
    }


    private fun showChangelogDialog() {
        val changelog = """
        v1.5
        - 使用更可靠的正则检测数据是否完整
        - 加入数据存在问题时的操作指引弹窗
        - Zxing 存在问题，暂时不用
        - 修改一些描述
        v1.4
        - 加入协程防止阻塞主线程
        - 使用离线 ML Kit 提高兼容性
        - 使用 Zxing 作为 fallback 提高识别率
        v1.3：
        - 支持识别科学计数法格式的 scale 值
        - 新增身高理论最值的显示
        - 修改缩放的逻辑，提高识别率
        - 新增版本号和日志显示
        v1.2
        - 新增图像增强，提高识别率
        - 新增自动多倍率缩放扫描，同上
        - 更换使用更广泛的身高算法
        v1.1
        - 修正 scale 不能为负值的bug
        - 修正不能完全解码导致的无法识别
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("更新日志")
            .setMessage(changelog)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showDataBrokenDialog() {
        val dataBrokenMsg = """
        目前本方法暂无法完全准确地解析和反序列化游戏内二维码原始内容，所以存在无法正确测算的可能。本二维码解析时即存在问题，目前暂无解决方案，十分抱歉。
    """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("出现错误")
            .setMessage(dataBrokenMsg)
            .setPositiveButton("关闭", null)
            .show()
    }

}
