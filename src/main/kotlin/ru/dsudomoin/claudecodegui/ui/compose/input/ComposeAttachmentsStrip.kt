package ru.dsudomoin.claudecodegui.ui.compose.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

// ── Data Models ──────────────────────────────────────────────────────────────

data class AttachedImageData(
    val fileName: String,
    val filePath: String,
)

data class MentionChipData(
    val fileName: String,
    val relativePath: String,
    val absolutePath: String,
)

data class FileContextData(
    val fileName: String,
    val lineRange: String,
    val filePath: String,
)

data class MentionSuggestionData(
    val fileName: String,
    val relativePath: String,
    val absolutePath: String,
    val isLibrary: Boolean = false,
    val libraryName: String? = null,
)

// ── Image Thumbnails Strip ───────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeImageStrip(
    images: List<AttachedImageData>,
    onImageClick: (String) -> Unit,
    onImageRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(4.dp),
    ) {
        images.forEach { img ->
            ImageChip(
                image = img,
                onClick = { onImageClick(img.filePath) },
                onRemove = { onImageRemove(img.filePath) },
            )
        }
    }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

@Composable
private fun ImageChip(
    image: AttachedImageData,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val ext = image.fileName.substringAfterLast('.', "").lowercase()
    val isImage = ext in IMAGE_EXTENSIONS

    // Load thumbnail asynchronously for image files
    val thumbnail by produceState<ImageBitmap?>(null, image.filePath) {
        if (isImage) {
            value = withContext(Dispatchers.IO) {
                try {
                    val file = java.io.File(image.filePath)
                    if (file.exists()) {
                        val buffered = javax.imageio.ImageIO.read(file) ?: return@withContext null
                        // Scale down to thumbnail
                        val maxDim = 96
                        val scale = minOf(maxDim.toFloat() / buffered.width, maxDim.toFloat() / buffered.height, 1f)
                        val w = (buffered.width * scale).toInt().coerceAtLeast(1)
                        val h = (buffered.height * scale).toInt().coerceAtLeast(1)
                        val scaled = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                        val g = scaled.createGraphics()
                        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        g.drawImage(buffered, 0, 0, w, h, null)
                        g.dispose()
                        scaled.toComposeImageBitmap()
                    } else null
                } catch (_: Exception) { null }
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.dropdownBg)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceTertiary),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = image.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                // File type icon for non-image files or loading state
                FileTypeIcon(fileName = image.fileName, size = 20.dp)
            }
        }

        // Filename + remove
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayName = if (image.fileName.length > 15) {
                image.fileName.take(12) + "\u2026"
            } else {
                image.fileName
            }
            Text(
                text = displayName,
                style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
            RemoveButton(onClick = onRemove)
        }
    }
}

// ── File Mention Chips ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComposeMentionStrip(
    mentions: List<MentionChipData>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mentions.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.padding(4.dp),
    ) {
        mentions.forEach { mention ->
            MentionChip(
                mention = mention,
                onRemove = { onRemove(mention.absolutePath) },
            )
        }
    }
}

@Composable
private fun MentionChip(
    mention: MentionChipData,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.dropdownBg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        FileTypeIcon(fileName = mention.fileName, size = 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = mention.fileName,
            style = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        RemoveButton(onClick = onRemove)
    }
}

// ── File Context Chip (active editor) ────────────────────────────────────────

@Composable
fun ComposeFileContextChip(
    context: FileContextData?,
    onFileClick: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (context == null) return
    val colors = LocalClaudeColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.dropdownBg)
            .clickable { onFileClick(context.filePath) }
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        FileTypeIcon(fileName = context.fileName, size = 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${context.fileName}${context.lineRange}",
            style = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(4.dp))
        RemoveButton(onClick = onRemove)
    }
}

// ── File Type Icon (real IntelliJ icons) ─────────────────────────────────────

/**
 * Renders the real IntelliJ file type icon for a given filename.
 * Falls back to a Canvas-drawn generic page icon if the icon can't be loaded.
 */
@Composable
fun FileTypeIcon(
    fileName: String,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    val iconBitmap = remember(fileName) {
        try {
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByFileName(fileName)
            val swingIcon = fileType.icon ?: return@remember null
            // Paint Swing icon onto a BufferedImage
            val w = swingIcon.iconWidth
            val h = swingIcon.iconHeight
            if (w <= 0 || h <= 0) return@remember null
            val buffered = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val g = buffered.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            swingIcon.paintIcon(null, g, 0, 0)
            g.dispose()
            buffered.toComposeImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = fileName,
            modifier = modifier.size(size),
        )
    } else {
        FallbackFileIcon(size = size, modifier = modifier)
    }
}

@Composable
private fun FallbackFileIcon(
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val iconColor = colors.textSecondary
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val fold = w * 0.3f
        val strokeW = w * 0.1f
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w - fold, 0f)
            lineTo(w, fold)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(path, iconColor, style = Stroke(width = strokeW))
        val foldPath = Path().apply {
            moveTo(w - fold, 0f)
            lineTo(w - fold, fold)
            lineTo(w, fold)
        }
        drawPath(foldPath, iconColor, style = Stroke(width = strokeW))
    }
}

// ── Shared Remove Button ─────────────────────────────────────────────────────

@Composable
private fun RemoveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(14.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isHovered) colors.iconHoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = "\u2715",
            style = TextStyle(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textSecondary,
            ),
        )
    }
}
