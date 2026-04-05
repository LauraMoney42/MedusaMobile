package com.medusa.mobile.agent.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// FileManagerTool — list, read, write, search, and delete files on device storage.
//
// Scope: public external directories only — Downloads, Documents, Pictures, Music,
// Movies, DCIM. Not app-private storage. Not root filesystem.
//
// Why plain File API over MediaStore?
//   For a conversational assistant the use cases are: "show my downloads",
//   "read that text file", "save a note to Documents". Direct File API is simpler
//   and more reliable for these cases than MediaStore queries. MediaStore excels
//   at large media libraries; File API is better for text-centric assistant work.
//
// Permissions:
//   Android ≤ 12: READ_EXTERNAL_STORAGE + WRITE_EXTERNAL_STORAGE
//   Android 13+:  READ_MEDIA_IMAGES / VIDEO / AUDIO (not needed for Documents/Downloads
//                 — those dirs are accessible without permission post-API 33)

class FileManagerTool(private val context: Context) {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    // ── Data models ──────────────────────────────────────────────────────────

    @Serializable
    data class FileEntry(
        val name: String,
        val path: String,
        val size: Long,
        val sizeHuman: String,      // "1.4 MB"
        val isDirectory: Boolean,
        val lastModified: String,   // "2024-03-15 14:30"
        val extension: String
    )

    @Serializable
    data class FileResult(
        val success: Boolean,
        val files: List<FileEntry> = emptyList(),
        val count: Int = 0,
        val content: String = "",   // for file_read
        val message: String = "",
        val path: String = ""       // confirmation path for file_write / file_delete
    )

    // ── Permission helpers ───────────────────────────────────────────────────

    private fun canRead(): Boolean {
        // Android 13+: Downloads/Documents don't need READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canWrite(): Boolean {
        // Android 10+ (API 29): scoped storage — apps write to their designated
        // public dirs without WRITE_EXTERNAL_STORAGE on API 29+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ── Known public directories ─────────────────────────────────────────────

    private val dirs: Map<String, File> = mapOf(
        "downloads"  to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "documents"  to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "pictures"   to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "music"      to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "movies"     to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        "dcim"       to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
    )

    private fun resolveDir(name: String?): File {
        if (name.isNullOrBlank()) return dirs["downloads"]!!
        val key = name.lowercase().trim()
        return dirs[key]
            ?: dirs.values.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: dirs["downloads"]!!
    }

    private fun File.toEntry() = FileEntry(
        name         = name,
        path         = absolutePath,
        size         = if (isFile) length() else 0L,
        sizeHuman    = if (isFile) humanSize(length()) else "—",
        isDirectory  = isDirectory,
        lastModified = dateFmt.format(Date(lastModified())),
        extension    = if (isFile) extension.lowercase() else ""
    )

    private fun humanSize(b: Long) = when {
        b < 1_024          -> "$b B"
        b < 1_048_576      -> "%.1f KB".format(b / 1_024.0)
        b < 1_073_741_824  -> "%.1f MB".format(b / 1_048_576.0)
        else               -> "%.1f GB".format(b / 1_073_741_824.0)
    }

    // Binary extensions Claude cannot interpret — refuse to read these
    private val binaryExts = setOf(
        "jpg","jpeg","png","gif","webp","bmp","heic",
        "mp4","mov","avi","mkv","webm",
        "mp3","aac","flac","ogg","wav","m4a",
        "pdf","zip","tar","gz","rar","7z",
        "apk","so","dex","bin","dat","exe","dll","class"
    )

    // ── file_list ────────────────────────────────────────────────────────────

    /**
     * Lists files in a public directory, newest-first.
     *
     * @param directory "downloads" | "documents" | "pictures" | "music" | "movies". Default "downloads".
     * @param extension Optional extension filter: "txt", "pdf", etc.
     * @param limit     Max entries. Default 30.
     */
    fun listFiles(
        directory: String? = "downloads",
        extension: String? = null,
        limit: Int = 30
    ): FileResult {
        if (!canRead()) return FileResult(
            success = false,
            message = "Storage read permission not granted. Grant it in Settings > Apps > Medusa > Permissions."
        )
        return try {
            val dir = resolveDir(directory)
            if (!dir.exists()) return FileResult(
                success = false, message = "Directory not found: ${dir.absolutePath}"
            )
            val ext = extension?.lowercase()?.trimStart('.')
            val entries = (dir.listFiles() ?: emptyArray())
                .filter { ext == null || it.extension.lowercase() == ext }
                .sortedByDescending { it.lastModified() }
                .take(limit)
                .map { it.toEntry() }

            FileResult(success = true, files = entries, count = entries.size, path = dir.absolutePath)
        } catch (e: Exception) {
            FileResult(success = false, message = "file_list failed: ${e.message}")
        }
    }

    // ── file_read ────────────────────────────────────────────────────────────

    /**
     * Reads text content from a file.
     *
     * @param path     Absolute path, or filename relative to Downloads.
     * @param maxChars Max characters to return. Default 8000 (fits in Claude context).
     */
    fun readFile(path: String, maxChars: Int = 8_000): FileResult {
        if (!canRead()) return FileResult(
            success = false, message = "Storage read permission not granted."
        )
        return try {
            val file = resolvePath(path)
                ?: return FileResult(success = false, message = "File not found: $path")
            if (file.isDirectory) return FileResult(
                success = false, message = "'${file.name}' is a directory, not a file."
            )
            if (file.extension.lowercase() in binaryExts) return FileResult(
                success = false,
                message = "Cannot read binary file (${file.extension.uppercase()}). " +
                        "Only text files are supported."
            )
            val raw = file.readText(Charsets.UTF_8)
            val out = if (raw.length > maxChars)
                raw.take(maxChars) + "\n\n[...truncated — ${raw.length} chars total, showing first $maxChars]"
            else raw

            FileResult(
                success = true,
                content = out,
                path = file.absolutePath,
                message = "Read '${file.name}' (${humanSize(file.length())})"
            )
        } catch (e: Exception) {
            FileResult(success = false, message = "file_read failed: ${e.message}")
        }
    }

    // ── file_write ───────────────────────────────────────────────────────────

    /**
     * Creates or overwrites a text file in the given public directory.
     * Always saves to the directory — no path traversal allowed.
     *
     * @param filename  File name only, e.g. "notes.txt".
     * @param content   Text to write.
     * @param directory Target directory. Default "documents".
     * @param overwrite If false and file exists, returns error without writing. Default true.
     */
    fun writeFile(
        filename: String,
        content: String,
        directory: String = "documents",
        overwrite: Boolean = true
    ): FileResult {
        if (!canWrite()) return FileResult(
            success = false, message = "Storage write permission not granted."
        )
        return try {
            val dir = resolveDir(directory).also { it.mkdirs() }
            // Strip any path separators — only allow a plain filename
            val safeName = File(filename).name.trim()
            if (safeName.isBlank()) return FileResult(success = false, message = "Invalid filename.")

            val file = File(dir, safeName)
            if (file.exists() && !overwrite) return FileResult(
                success = false,
                message = "File already exists: ${file.absolutePath}. Pass overwrite=true to replace."
            )
            file.writeText(content, Charsets.UTF_8)
            FileResult(
                success = true,
                path = file.absolutePath,
                message = "Saved '${file.name}' (${humanSize(file.length())}) to ${dir.name}"
            )
        } catch (e: Exception) {
            FileResult(success = false, message = "file_write failed: ${e.message}")
        }
    }

    // ── file_search ──────────────────────────────────────────────────────────

    /**
     * Recursively searches for files by name substring across public directories.
     *
     * @param query     Filename substring (case-insensitive). Required.
     * @param extension Optional extension filter: "txt", "pdf".
     * @param directory Limit search to one directory. Searches all if omitted.
     * @param limit     Max results. Default 20.
     */
    fun searchFiles(
        query: String?,
        extension: String? = null,
        directory: String? = null,
        limit: Int = 20
    ): FileResult {
        if (!canRead()) return FileResult(
            success = false, message = "Storage read permission not granted."
        )
        return try {
            val searchDirs = if (!directory.isNullOrBlank())
                listOf(resolveDir(directory))
            else
                dirs.values.toList()

            val ext = extension?.lowercase()?.trimStart('.')
            val q   = query?.lowercase()
            val hits = mutableListOf<FileEntry>()

            outer@ for (dir in searchDirs) {
                if (!dir.exists()) continue
                for (f in dir.walkTopDown()) {
                    if (!f.isFile) continue
                    if (q != null && !f.name.lowercase().contains(q)) continue
                    if (ext != null && f.extension.lowercase() != ext) continue
                    hits += f.toEntry()
                    if (hits.size >= limit) break@outer
                }
            }
            val sorted = hits.sortedByDescending { it.lastModified }
            FileResult(success = true, files = sorted, count = sorted.size)
        } catch (e: Exception) {
            FileResult(success = false, message = "file_search failed: ${e.message}")
        }
    }

    // ── file_delete ──────────────────────────────────────────────────────────

    /**
     * Deletes a file. Claude MUST confirm with the user before calling this.
     *
     * @param path Absolute path, or filename relative to Downloads.
     */
    fun deleteFile(path: String): FileResult {
        if (!canWrite()) return FileResult(
            success = false, message = "Storage write permission not granted."
        )
        return try {
            val file = resolvePath(path)
                ?: return FileResult(success = false, message = "File not found: $path")
            if (file.isDirectory) return FileResult(
                success = false, message = "Cannot delete directories — only files."
            )
            val name = file.name
            if (file.delete())
                FileResult(success = true, message = "Deleted: $name", path = file.absolutePath)
            else
                FileResult(success = false, message = "Could not delete '$name'. It may be locked or already gone.")
        } catch (e: Exception) {
            FileResult(success = false, message = "file_delete failed: ${e.message}")
        }
    }

    // ── Path resolver ────────────────────────────────────────────────────────

    private fun resolvePath(path: String): File? {
        val f = File(path)
        if (f.isAbsolute && f.exists()) return f
        // Try relative to each public directory
        for (dir in dirs.values) {
            val candidate = File(dir, path)
            if (candidate.exists()) return candidate
        }
        return null
    }
}
