package fr.descentecanyon.app.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

data class DcShapeTokens(
    val xs: RoundedCornerShape = RoundedCornerShape(6.dp),
    val sm: RoundedCornerShape = RoundedCornerShape(10.dp),
    val md: RoundedCornerShape = RoundedCornerShape(16.dp),
    val lg: RoundedCornerShape = RoundedCornerShape(22.dp),
    val xl: RoundedCornerShape = RoundedCornerShape(28.dp),
    val xxl: RoundedCornerShape = RoundedCornerShape(36.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
)

val DcShapeTokensDefault = DcShapeTokens()

val DcShapes = Shapes(
    extraSmall = DcShapeTokensDefault.xs,
    small = DcShapeTokensDefault.sm,
    medium = DcShapeTokensDefault.md,
    large = DcShapeTokensDefault.lg,
    extraLarge = DcShapeTokensDefault.xl,
)

val LocalDcShapes = staticCompositionLocalOf { DcShapeTokensDefault }
