package gg.hideouthq.feature.discover.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.AvatarFill
import gg.hideouthq.core.designsystem.component.AvatarShape
import gg.hideouthq.core.designsystem.component.HeroAvatar
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.component.SectionHeader
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.discover.HeroItem
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_all_heroes
import gg.hideouthq.resources.discover_browse_by_hero
import gg.hideouthq.resources.discover_keyword_hero
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HeroStrip(
    heroes: List<HeroItem>,
    heroCount: Int,
    onHero: (String) -> Unit,
    onAllHeroes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.BlockGap)) {
        SectionHeader(
            title = stringResource(Res.string.discover_browse_by_hero),
            trailing = { LinkButton(stringResource(Res.string.discover_all_heroes, heroCount), onAllHeroes) },
            icon = HideoutIcons.SectionHero,
            accent = HideoutTheme.colors.sectionHero,
            keyword = stringResource(Res.string.discover_keyword_hero),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val count = ((maxWidth + HideoutDimens.HeroAvatar.ItemGap) / (HideoutDimens.HeroAvatar.MinItemWidth + HideoutDimens.HeroAvatar.ItemGap)).toInt().coerceAtLeast(1)
            Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.HeroAvatar.ItemGap)) {
                val shown = heroes.take(count)
                shown.forEachIndexed { index, hero ->
                    HeroAvatar(
                        initials = hero.initials,
                        name = hero.name,
                        shape = AvatarShape.forIndex(index),
                        fill = if (hero.featured) AvatarFill.Highlight else AvatarFill.forIndex(index),
                        onClick = { onHero(hero.id) },
                        modifier = Modifier.weight(1f),
                        imageUrl = hero.iconUrl,
                    )
                }
                repeat(count - shown.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
