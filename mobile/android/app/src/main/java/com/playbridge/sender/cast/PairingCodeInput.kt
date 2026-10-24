package com.playbridge.sender.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun PairingCodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    isError: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    BasicTextField(code, { input ->
        val digits = input.filter(Char::isDigit).take(6)
        if (digits != code) onCodeChange(digits)
    }, singleLine = true, enabled = enabled,
        // Number, not NumberPassword: a pairing code shouldn't summon password managers.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .semantics { contentDescription = "6-digit code, ${code.length} entered" },
        decorationBox = { _ ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { index ->
                    val next = index == code.length
                    val border = when {
                        isError && code.isEmpty() -> MaterialTheme.colorScheme.error
                        next -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(Modifier.weight(1f).height(56.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .border(if (next) 2.dp else 1.dp, border, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(code.getOrNull(index)?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        })
    LaunchedEffect(autoFocus, enabled) { if (autoFocus && enabled) focus.requestFocus() }
}
