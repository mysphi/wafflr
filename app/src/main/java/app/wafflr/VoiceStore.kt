package app.wafflr

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Installs voice packs into internal storage so they work offline. */
class VoiceStore(private val context: Context) {
    private val root = File(context.filesDir, "voices")

    fun dirFor(pack: VoicePack) = File(root, pack.dirName)

    fun isInstalled(pack: VoicePack) = File(dirFor(pack), ".installed").exists()

    /** Blocking. Call from a background thread. [progress] receives human-readable status. */
    @Synchronized
    fun install(pack: VoicePack, progress: (String) -> Unit) {
        if (isInstalled(pack)) return
        root.mkdirs()
        val tmp = File(root, pack.dirName + ".tmp")
        tmp.deleteRecursively()
        if (pack.bundled) {
            progress("Unpacking built-in voice…")
            copyFromAssets(pack, tmp)
        } else {
            val archive = File(context.cacheDir, pack.dirName + ".tar.bz2")
            download(pack.url, archive, progress)
            progress("Checking the download…")
            if (pack.sha256.isNotEmpty() && sha256(archive) != pack.sha256) {
                archive.delete()
                error("the download didn't match the expected voice file, so it wasn't installed. Please try again.")
            }
            progress("Unpacking ${pack.dirName}… (this can take a minute)")
            extract(archive, tmp)
            archive.delete()
        }
        // Archives contain a top-level folder named after the pack.
        val inner = File(tmp, pack.dirName)
        val finalSource = if (inner.isDirectory) inner else tmp
        val target = dirFor(pack)
        target.deleteRecursively()
        if (!finalSource.renameTo(target)) error("Could not move ${finalSource} to $target")
        tmp.deleteRecursively()
        File(target, ".installed").writeText(System.currentTimeMillis().toString())
        progress("Installed ${pack.dirName}")
    }

    private fun copyFromAssets(pack: VoicePack, dest: File) {
        val base = "voices/${pack.dirName}"
        val files = context.assets.open("$base/files.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        for (rel in files) {
            val out = File(dest, rel)
            out.parentFile?.mkdirs()
            context.assets.open("$base/$rel").use { input ->
                FileOutputStream(out).use { input.copyTo(it, 1 shl 16) }
            }
        }
    }

    private fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, dest: File, progress: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) error("Download failed: HTTP ${conn.responseCode} for $url")
        val total = conn.contentLengthLong
        var done = 0L
        var lastReport = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 500) {
                        lastReport = now
                        val mb = done / 1_000_000
                        progress(if (total > 0) "Downloading… $mb of ${total / 1_000_000} MB" else "Downloading… $mb MB")
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun extract(archive: File, dest: File) {
        dest.mkdirs()
        val canonicalDest = dest.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(canonicalDest)) continue // ignore unsafe paths
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isFile) {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    /** Removes a pack's files. A built-in pack can be unpacked again from the app later. */
    fun delete(pack: VoicePack) {
        dirFor(pack).deleteRecursively()
    }

    fun installedPacks(): List<VoicePack> = VoiceCatalog.packs.filter { isInstalled(it) }

    /** Space a pack's files take up, in bytes. */
    fun sizeOf(pack: VoicePack): Long = dirFor(pack).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Tidies up at start-up: removes folders of packs Wafflr no longer offers, half-finished
     * installs and stale downloads, then makes sure at least one voice is ready (the built-in one).
     * Blocking. Call from a background thread.
     */
    fun housekeeping() {
        for (name in VoiceCatalog.retiredDirs) File(root, name).deleteRecursively()
        root.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles { f -> f.name.endsWith(".tar.bz2") }?.forEach { it.delete() }
        if (installedPacks().isEmpty()) install(VoiceCatalog.piperAlan) {}
    }
}
