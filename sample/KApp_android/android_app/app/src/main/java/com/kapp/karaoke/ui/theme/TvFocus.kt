package com.kapp.karaoke.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.tvFocusBorder(
    shape: Shape = RoundedCornerShape(8.dp),
    focusedBorderColor: Color = Color.White,
    borderWidth: Dp = 2.5.dp,
    focusedBackgroundColor: Color = Color(0x556C63FF)
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { isFocused = it.isFocused }
        .background(if (isFocused) focusedBackgroundColor else Color.Transparent, shape = shape)
        .border(
            width = if (isFocused) borderWidth else 0.dp,
            color = if (isFocused) focusedBorderColor else Color.Transparent,
            shape = shape
        )
}
