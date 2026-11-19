package com.example.smartuse_frontend.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// DESIGN.md `rounded` scale: sm=2px, DEFAULT=4px, md=6px, lg=8px, xl=12px.
// `full` (9999px) is deliberately never used here — Status Indicator
// pills must stop at `xl` and never read as fully circular.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)
