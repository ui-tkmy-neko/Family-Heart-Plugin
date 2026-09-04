package nekouidaga.net.familyheartplugin.request

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Async request audit log. Requests themselves are transient; this preserves history without DB coupling. */
class RequestLogService(private val dir: Path, private val maxBytes: Long = 5L * 1024L * 1024L) {
    private val queue = LinkedBlockingQueue<String>()
    private val running = AtomicBoolean(true)
    private val sequence = AtomicInteger(nextSequence())
    private val worker = Thread({ loop() }, "FamilyHeart-RequestLog").apply { isDaemon = true }
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    init {
        Files.createDirectories(dir)
        worker.start()
    }

    fun log(message: String) {
        if (running.get()) queue.offer("[${fmt.format(LocalDateTime.now())}] $message")
    }

    private fun nextSequence(): Int {
        if (!Files.exists(dir)) return 1
        return Files.list(dir).use { stream ->
            val names = mutableListOf<String>()
            stream.forEach { names += it.fileName.toString() }
            names.mapNotNull { Regex("log-(\\d+)\\.log\\.gz").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                .maxOrNull()?.plus(1) ?: 1
        }
    }

    private fun loop() {
        var writer: BufferedWriter? = null
        try {
            while (running.get() || queue.isNotEmpty()) {
                val line = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (writer == null) writer = openLatest()
                rotateIfNeeded(writer).also { writer = it }
                writer!!.append(line).append('\n')
                writer!!.flush()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try { writer?.close() } catch (_: Exception) {}
        }
    }

    private fun openLatest(): BufferedWriter {
        val path = dir.resolve("latest.log")
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun rotateIfNeeded(writer: BufferedWriter): BufferedWriter {
        val path = dir.resolve("latest.log")
        if (!Files.exists(path) || Files.size(path) < maxBytes) return writer
        writer.close()
        val rotated = dir.resolve("log-%04d.log".format(sequence.getAndIncrement()))
        Files.move(path, rotated)
        try {
            val gz = dir.resolve(rotated.fileName.toString() + ".gz")
            Files.newInputStream(rotated).use { input ->
                Files.newOutputStream(gz, StandardOpenOption.CREATE_NEW).use { output ->
                    GZIPOutputStream(output).use { gzip -> input.copyTo(gzip) }
                }
            }
            Files.deleteIfExists(rotated)
        } catch (_: Exception) {
            // Keep the uncompressed rotated file if compression fails.
        }
        return openLatest()
    }

    fun shutdown() {
        running.set(false)
        try { worker.join(5_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}
