package com.example.pipeline

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.AspectRatioOption
import com.example.data.model.ThumbnailStyle
import com.example.data.model.VideoProject
import java.io.File

class MediaExportHelper(private val context: Context) {

    suspend fun saveMp4ToDownloads(project: VideoProject): Result<String> = runCatching {
        val path = project.localVideoPath ?: throw IllegalStateException("Rendered MP4 file is missing.")
        val file = File(path)
        NativeMp4VideoEngine.exportMp4ToDownloads(context, file, project.title)
    }

    suspend fun regenerateAndSaveThumbnail(
        project: VideoProject,
        customTitle: String,
        style: ThumbnailStyle,
        firstSceneImagePath: String?,
        exportToDownloads: Boolean,
        asPng: Boolean
    ): Result<Pair<String, String?>> = runCatching {
        val projectDir = File(context.filesDir, "sarvcut/${project.id}").apply { mkdirs() }
        val ext = if (asPng) "png" else "jpg"
        val outThumb = File(projectDir, "thumbnail_${style.name.lowercase()}.$ext")
        val sourceImg = firstSceneImagePath?.let { File(it) }?.takeIf { it.exists() }
        ThumbnailEngine.generateThumbnail(
            context = context,
            title = customTitle.ifBlank { project.title },
            formatLabel = project.formatEnum().label,
            sourceImageFile = sourceImg,
            aspectRatio = AspectRatioOption.fromWire(project.aspectRatio),
            style = style,
            outFile = outThumb
        )
        val downloadUri = if (exportToDownloads) {
            ThumbnailEngine.exportThumbnailToDownloads(
                context = context,
                sourceFile = outThumb,
                fileNameWithoutExt = "${project.title}_thumb_${style.name.lowercase()}",
                asPng = asPng
            )
        } else null
        outThumb.absolutePath to downloadUri
    }

    fun shareMp4File(project: VideoProject): Result<Unit> = runCatching {
        val path = project.localVideoPath ?: throw IllegalStateException("MP4 file not available.")
        val file = File(path)
        require(file.exists()) { "MP4 file not found on device." }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, project.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${project.title}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
