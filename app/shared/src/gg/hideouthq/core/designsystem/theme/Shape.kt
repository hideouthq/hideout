package gg.hideouthq.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object HideoutRadius {
    val Pane = 18.dp
    val Hero = 16.dp
    val Stage = 16.dp
    val Card = 14.dp
    val Row = 12.dp
    val GameTile = 11.dp
    val Thumb = 10.dp
    val Tile = 7.dp
    val EmptyStateTile = 18.dp
    val AvatarSquare = 20.dp
    val Toolbar = 28.dp
    val Field = 14.dp
    val FieldCompact = 11.dp
    val FilterChip = 10.dp
    val Editor = 16.dp

    val ButtonPressed = 14.dp
    val Tab = 12.dp
    val TabIndicator = 3.dp
    val SettingsGroupEdge = 20.dp
    val SettingsGroupInner = 6.dp
    val SettingsTile = 13.dp
}

object HideoutShapes {
    val Pane: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Pane)
    val Hero: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Hero)
    val Stage: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Stage)
    val Card: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Card)
    val Row: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Row)
    val GameTile: RoundedCornerShape = RoundedCornerShape(HideoutRadius.GameTile)
    val Thumb: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Thumb)
    val Tile: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Tile)
    val EmptyStateTile: RoundedCornerShape = RoundedCornerShape(HideoutRadius.EmptyStateTile)
    val Toolbar: RoundedCornerShape = RoundedCornerShape(HideoutRadius.Toolbar)
    val Tab: RoundedCornerShape = RoundedCornerShape(topStart = HideoutRadius.Tab, topEnd = HideoutRadius.Tab)
    val TabIndicator: RoundedCornerShape = RoundedCornerShape(topStart = HideoutRadius.TabIndicator, topEnd = HideoutRadius.TabIndicator)
    val SettingsTile: RoundedCornerShape = RoundedCornerShape(HideoutRadius.SettingsTile)
    val Full: Shape = CircleShape
}

internal val HideoutMaterialShapes = Shapes(
    extraSmall = HideoutShapes.Tile,
    small = HideoutShapes.Thumb,
    medium = HideoutShapes.Row,
    large = HideoutShapes.Card,
    extraLarge = HideoutShapes.Hero,
)
