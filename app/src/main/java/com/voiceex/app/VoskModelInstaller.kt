package com.voiceex.app

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

internal object VoskModelInstaller {
    private const val MODEL_NAME = "vosk-model-en-us-0.22-lgraph"
    private const val OLD_MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL =
        "https://github.com/forcontactmail/Voice_EXApp/releases/download/v1.2.104/$MODEL_NAME.zip"

    fun install(context: Context): File {
        val modelDirectory = File(context.filesDir, MODEL_NAME)
        if (File(modelDirectory, "am/final.mdl").isFile) {
            File(context.filesDir, OLD_MODEL_NAME).deleteRecursively()
            return modelDirectory
        }

        val stagingDirectory = File(context.filesDir, "$MODEL_NAME.tmp")
        val archive = File(context.cacheDir, "$MODEL_NAME.zip")
        stagingDirectory.deleteRecursively()
        modelDirectory.deleteRecursively()

        try {
            download(archive)
            extract(archive, stagingDirectory)
            if (!File(stagingDirectory, "am/final.mdl").isFile) {
                throw IOException("The downloaded English speech model is incomplete.")
            }
            if (!stagingDirectory.renameTo(modelDirectory)) {
                throw IOException("Could not install the English speech model.")
            }
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            throw error
        } finally {
            archive.delete()
        }

        File(context.filesDir, OLD_MODEL_NAME).deleteRecursively()
        return modelDirectory
    }

    private fun download(destination: File) {
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            val expectedLength = connection.contentLengthLong
            if (expectedLength > 0 && expectedLength > 200L * 1024 * 1024) {
                throw IOException("The English speech model archive is unexpectedly large.")
            }
            var downloadedLength = 0L
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedLength += count
                    }
                }
            }
            if (expectedLength > 0 && downloadedLength != expectedLength) {
                throw IOException("The English speech model download was incomplete.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: File, destination: File) {
        val destinationPath = destination.canonicalPath + File.separator
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relativePath = entry.name.removePrefix("$MODEL_NAME/")
                if (relativePath.isNotEmpty()) {
                    val outputFile = File(destination, relativePath).canonicalFile
                    if (!outputFile.path.startsWith(destinationPath)) {
                        throw IOException("The speech model archive contains an invalid path.")
                    }
                    if (entry.isDirectory) {
                        if (!outputFile.mkdirs() && !outputFile.isDirectory) {
                            throw IOException("Could not unpack the English speech model.")
                        }
                    } else {
                        outputFile.parentFile?.let { parent ->
                            if (!parent.mkdirs() && !parent.isDirectory) {
                                throw IOException("Could not unpack the English speech model.")
                            }
                        }
                        outputFile.outputStream().buffered().use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}