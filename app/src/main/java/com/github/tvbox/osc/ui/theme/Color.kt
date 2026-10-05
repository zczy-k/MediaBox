package com.github.tvbox.osc.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ColorScheme.cardContainer: Color
    get() = surfaceBright

@Composable
fun ColorScheme.filterChipColors(containerColor: Color = Color.Transparent): SelectableChipColors =
    FilterChipDefaults.filterChipColors(
        containerColor = containerColor,
        selectedContainerColor = primaryContainer,
        selectedLabelColor = onPrimaryContainer,
    )
