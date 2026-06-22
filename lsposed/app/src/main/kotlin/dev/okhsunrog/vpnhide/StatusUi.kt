package dev.okhsunrog.vpnhide

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Centralised status palette.
 *
 * Material You remixes `colorScheme.errorContainer` / `tertiaryContainer`
 * toward the wallpaper, which on real devices landed on "lavender" / "pink"
 * that read as a note, not a problem. So every status card and banner pins
 * these hand-picked container + accent colors instead of the theme's.
 * Defined once here rather than copy-pasted into each card.
 */
internal object StatusColors {
    @Composable
    private fun container(
        darkArgb: Long,
        darkAlpha: Float,
        lightArgb: Long,
    ): Color = if (isSystemInDarkTheme()) Color(darkArgb).copy(alpha = darkAlpha) else Color(lightArgb)

    @Composable fun successContainer() = container(0xFF1B5E20, 0.3f, 0xFFE8F5E9)

    @Composable fun warningContainer() = container(0xFFE65100, 0.2f, 0xFFFFF3E0)

    @Composable fun errorContainer() = container(0xFFB71C1C, 0.3f, 0xFFFFEBEE)

    @Composable fun infoContainer() = container(0xFF0D47A1, 0.28f, 0xFFE3F2FD)

    // Distinct from warning only in dark mode — the "install zygisk instead"
    // recommendation card uses a brown tint where warnings use orange.
    @Composable fun zygiskRecommendContainer() = container(0xFF4E342E, 0.32f, 0xFFFFF3E0)

    @Composable fun errorHeader() = if (isSystemInDarkTheme()) Color(0xFFEF9A9A) else Color(0xFFC62828)

    @Composable fun warningHeader() = if (isSystemInDarkTheme()) Color(0xFFFFB74D) else Color(0xFFE65100)

    // Accent colors (status dots / status text / pass-fail badges). These are
    // fixed regardless of theme — they sit on the tinted containers above.
    val successDot = Color(0xFF4CAF50)
    val successBadge = Color(0xFF2E7D32)
    val warningAccent = Color(0xFFFF9800)
    val errorDot = Color(0xFFB71C1C)
    val errorAccent = Color(0xFFC62828)
}

/**
 * Flat colored banner card. Shared by the Dashboard protection / issues
 * banners and the Diagnostics status banners (it was a private duplicate in
 * both screens).
 */
@Composable
internal fun StatusBanner(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Share a cache file via FileProvider + ACTION_SEND chooser. Identical
 * logic was inlined for both the debug-zip and the logcat export. */
internal fun shareFileViaProvider(
    context: Context,
    file: File,
    mimeType: String,
) {
    val uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(intent, null))
}

/**
 * Save + Share button row used by both Diagnostics export flows. [sharePrimary]
 * paints the Share button filled (debug-zip) vs outlined (logcat).
 */
@Composable
internal fun FileSaveShareRow(
    saveLabel: String,
    shareLabel: String,
    sharePrimary: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(saveLabel)
        }
        val shareContent: @Composable () -> Unit = {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(shareLabel)
        }
        if (sharePrimary) {
            Button(onClick = onShare, modifier = Modifier.weight(1f)) { shareContent() }
        } else {
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { shareContent() }
        }
    }
}
