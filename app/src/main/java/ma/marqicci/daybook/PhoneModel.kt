package ma.marqicci.daybook

import android.content.Context
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ma.marqicci.inference.LocalInference
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.net.ssl.HttpsURLConnection

private const val DOWNLOAD = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/ddde4293f6050e8b81cca2ea2438c2fe444a0d5b/SmolLM2-360M-Instruct-Q4_K_M.gguf"
private const val MODEL_HASH = "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2"
data class ModelStatus(val ready: Boolean, val copiedMB: Int? = null, val error: String = "")

class PhoneModel(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "smollm2-360m-q4km.gguf")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inference = Mutex()
    private var transfer: Job? = null
    val supported = Process.is64Bit()
    val status = MutableStateFlow(ModelStatus(file.isFile))

    // Called on the main thread. The transfer survives a rotation or tab change.
    fun install(uri: Uri? = null) {
        if (!supported || status.value.ready || transfer?.isActive == true) return
        status.value = ModelStatus(false, 0)
        transfer = scope.launch {
            val part = File(file.parentFile, file.name + ".part")
            var connection: HttpsURLConnection? = null
            try {
                part.delete()
                check(file.parentFile!!.usableSpace >= 280_000_000L) { "Free at least 280 MB and try again." }
                val stream: InputStream = if (uri != null) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Could not open the selected file." }
                } else {
                    val http = URL(DOWNLOAD).openConnection() as HttpsURLConnection
                    connection = http
                    http.connectTimeout = 15000
                    http.readTimeout = 30000
                    check(http.responseCode in 200..299) { "The model host returned HTTP ${http.responseCode}. Retry or import the model file." }
                    http.inputStream
                }
                val hash = MessageDigest.getInstance("SHA-256")
                stream.use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total < 280_000_000L) { "Choose the 271 MB SmolLM2-360M-Instruct Q4_K_M file." }
                            hash.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            status.value = ModelStatus(false, (total / 1_000_000).toInt())
                        }
                    }
                }
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                check(digest == MODEL_HASH) { "File verification failed. Download again or choose the matching Q4_K_M file." }
                ensureActive()
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                status.value = ModelStatus(true)
            } catch (e: CancellationException) {
                status.value = ModelStatus(file.isFile)
                throw e
            } catch (e: Exception) {
                status.value = ModelStatus(file.isFile, error = e.message ?: "Model installation failed.")
            } finally {
                connection?.disconnect()
                part.delete()
            }
        }
    }
    fun cancelInstall() { transfer?.cancel() }

    suspend fun generate(instruction: String, input: String, limit: Int = 140): String = inference.withLock {
        check(supported && file.isFile) { "Install the phone model in Workflows first." }
        fun clean(s: String) = s.replace("<|", "< |")
        val prompt = "<|im_start|>system\n${clean(instruction)}<|im_end|>\n<|im_start|>user\n${clean(input)}<|im_end|>\n<|im_start|>assistant\n"
        val result = runInterruptible(Dispatchers.Default) {
            try {
                LocalInference.generate(file.absolutePath, prompt.toByteArray(), limit).toString(Charsets.UTF_8).trim()
            } catch (e: LinkageError) { throw IllegalStateException("The inference runtime could not load on this device.", e) }
        }
        check(result.isNotEmpty()) { "The model returned an empty answer. Revise the instruction and retry." }
        result
    }
}
