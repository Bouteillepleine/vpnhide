package dev.okhsunrog.vpnhide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.okhsunrog.vpnhide.ui.components.PreferenceRow

// Donation rails, deliberately Kotlin constants rather than translatable string
// resources: a wallet address must be byte-identical in every locale and must
// never pass through a translation round-trip.
//
// A blank constant hides its row, so an unfinished rail simply doesn't render.
internal const val DONATE_BOOSTY_URL = "https://boosty.to/okhsunrog"
internal const val DONATE_USDT_TRC20_ADDRESS = "TMskx2wKmPg11VYvHoS93vUQGm7yhcetUU"
internal const val DONATE_BTC_ADDRESS = "bc1pmt9u6nux4x7n86zknwdgt9v02lah2tu6d983ak2prc5cwt8hsetq82ganh"

/** GRAM is the coin of The Open Network — the ticker doesn't name its chain. */
internal const val DONATE_GRAM_ADDRESS = "UQADYTtMBQdZvmNNEX02R9sACpdnXKlPV8RbuFrxo7JFBRGS"

internal const val DONATE_LTC_ADDRESS = "MBLKJfPNANH3U41UPJFtha7EPJGdbiW5dZ"

/** One tap-to-copy crypto row: a display label and the address it copies. */
private data class CryptoRail(
    val label: String,
    val address: String,
)

/**
 * "Support the project" modal — Boosty for cards, a few crypto addresses that
 * copy to the clipboard on tap, and a link to the full list.
 *
 * Opened from Settings. Like [ContactModal] it only ever fires an
 * [Intent.ACTION_VIEW]: the app itself makes no network request and carries no
 * payment SDK.
 */
@Composable
internal fun DonateModal(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    // Locale-gated: Boosty is unreachable from mainland China (see bools.xml).
    val showBoosty = booleanResource(R.bool.donate_show_boosty) && DONATE_BOOSTY_URL.isNotBlank()

    val rails =
        listOf(
            CryptoRail(stringResource(R.string.donate_usdt), DONATE_USDT_TRC20_ADDRESS),
            CryptoRail(stringResource(R.string.donate_btc), DONATE_BTC_ADDRESS),
            CryptoRail(stringResource(R.string.donate_gram), DONATE_GRAM_ADDRESS),
            CryptoRail(stringResource(R.string.donate_ltc), DONATE_LTC_ADDRESS),
        ).filter { it.address.isNotBlank() }

    fun open(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.donate_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.donate_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (showBoosty) {
                    PreferenceRow(
                        title = stringResource(R.string.donate_boosty),
                        subtitle = stringResource(R.string.donate_boosty_sub),
                        icon = Icons.Default.Favorite,
                        onClick = { open(DONATE_BOOSTY_URL) },
                    )
                }
                rails.forEach { rail ->
                    PreferenceRow(
                        title = rail.label,
                        subtitle = shortenAddress(rail.address),
                        icon = Icons.Default.AccountBalanceWallet,
                        onClick = {
                            copyAddressToClipboard(context, rail.label, rail.address)
                            copied = true
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                if (copied) {
                    Text(
                        text = stringResource(R.string.donate_copied),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.donate_close)) }
        },
    )
}

/**
 * Middle-elided address for the row subtitle — enough to eyeball against the
 * README, short enough that three rails still fit in a dialog. The clipboard
 * always gets the full string.
 */
private fun shortenAddress(address: String): String =
    if (address.length <= ADDRESS_PREVIEW_KEEP * 2) {
        address
    } else {
        address.take(ADDRESS_PREVIEW_KEEP) + "…" + address.takeLast(ADDRESS_PREVIEW_KEEP)
    }

private const val ADDRESS_PREVIEW_KEEP = 8

private fun copyAddressToClipboard(
    context: Context,
    label: String,
    address: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, address))
}
