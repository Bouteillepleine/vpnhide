package dev.okhsunrog.vpnhide

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * The collapsed in-place search top bar shared by the app-picker and hidden-apps
 * screens: a full-width [SearchBar] whose leading arrow closes search ([onClose])
 * and whose trailing clear button appears only when [query] is non-empty.
 *
 * The caller's [query] String stays the source of truth (it also drives the list
 * filtering upstream); the [SearchBarState]/[androidx.compose.foundation.text.input.TextFieldState]
 * the new Material 3 API requires live here and are bridged to it. The bar is
 * only ever used collapsed — results render on the screen behind it, not in an
 * expanded overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(query)
    val currentQuery by rememberUpdatedState(query)

    // Push external resets (clear-on-close, programmatic changes) into the field.
    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }
    // Push user edits back out to the caller (rememberUpdatedState keeps the
    // comparison against the latest query, not the one captured at launch).
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { if (it != currentQuery) onQueryChange(it) }
    }

    SearchBar(
        state = searchBarState,
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = {},
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}
