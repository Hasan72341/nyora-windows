package com.nyora.windows.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyora.windows.NavDest
import com.nyora.windows.ui.theme.LocalNyoraAccent
import com.nyora.windows.ui.theme.NyoraTokens

/** A sidebar entry. [dest] null + [isSearch] = the global-search trigger (not a destination). */
private data class NavItem(
    val dest: NavDest?,
    val icon: ImageVector,
    val label: String,
    val isSearch: Boolean = false,
)

private data class NavSection(val title: String, val items: List<NavItem>)

/** Grouped navigation mirroring the macOS sidebar: Library / Discover / Reading / App. */
private val NAV_SECTIONS = listOf(
    NavSection(
        "Library",
        listOf(
            NavItem(NavDest.HISTORY, Icons.Default.History, "History"),
            NavItem(NavDest.FAVOURITES, Icons.Default.FavoriteBorder, "Favourites"),
            NavItem(NavDest.LOCAL_FILES, Icons.Default.FolderOpen, "Local"),
            NavItem(NavDest.BOOKMARKS, Icons.Default.BookmarkBorder, "Bookmarks"),
            NavItem(NavDest.DOWNLOADS, Icons.Default.Download, "Downloads"),
        ),
    ),
    NavSection(
        "Discover",
        listOf(
            NavItem(NavDest.EXPLORE, Icons.Default.Explore, "Explore"),
            NavItem(NavDest.SUGGESTIONS, Icons.Default.AutoAwesome, "For You"),
            NavItem(NavDest.UPDATES, Icons.Default.Autorenew, "Updates"),
        ),
    ),
    NavSection(
        "Reading",
        listOf(
            NavItem(NavDest.STATS, Icons.Default.BarChart, "Stats"),
            NavItem(null, Icons.Default.Search, "Search", isSearch = true),
        ),
    ),
    NavSection(
        "App",
        listOf(
            NavItem(NavDest.SETTINGS, Icons.Default.Settings, "Settings"),
        ),
    ),
)

private val SIDEBAR_WIDTH = 248.dp
private val SIDEBAR_WIDTH_COMPACT = 76.dp

/**
 * Full-height navigation rail. Flush to the left edge, separated from content by a hairline.
 * The NYORA brand sits on top; below it the destinations are grouped into captioned sections
 * (Library / Discover / Reading / App). The selected row gets an accent gradient wash plus a
 * left accent bar. Below ~900dp the host passes [isCompact] = true → an icon-only rail.
 *
 * [onSearch] opens the global-search overlay (the "Search" row has no destination).
 */
@Composable
fun NyoraSidebar(
    current: NavDest,
    onSelect: (NavDest) -> Unit,
    onSearch: () -> Unit = {},
    isCompact: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (isCompact) SIDEBAR_WIDTH_COMPACT else SIDEBAR_WIDTH)
            .background(NyoraTokens.bg)
            .padding(horizontal = if (isCompact) 8.dp else 14.dp)
            .padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = if (isCompact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        BrandRow(isCompact)

        Spacer(Modifier.height(10.dp))
        Hairline()
        Spacer(Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = if (isCompact) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            NAV_SECTIONS.forEachIndexed { index, section ->
                if (isCompact) {
                    if (index > 0) {
                        Spacer(Modifier.height(6.dp)); Hairline(); Spacer(Modifier.height(6.dp))
                    }
                } else {
                    SectionHeader(section.title)
                }
                section.items.forEach { item ->
                    NavRow(
                        selected = item.dest != null && current == item.dest,
                        icon = item.icon,
                        label = item.label,
                        isCompact = isCompact,
                        onClick = { if (item.isSearch) onSearch() else item.dest?.let(onSelect) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandRow(isCompact: Boolean) {
    if (isCompact) {
        Image(
            painter = painterResource("nyora_logo.png"),
            contentDescription = "Nyora",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(CircleShape),
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource("nyora_logo.png"),
            contentDescription = "Nyora",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(CircleShape),
        )
        Text(
            text = "NYORA",
            color = NyoraTokens.onSurfaceHigh,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier.fillMaxWidth().height(0.5.dp).background(
            Brush.horizontalGradient(
                listOf(Color.Transparent, NyoraTokens.hairlineFaint, Color.Transparent),
            ),
        ),
    )
}

@Composable
private fun SectionHeader(title: String) {
    val accent = LocalNyoraAccent.current.color
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 14.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.width(2.dp).height(13.dp).clip(RoundedCornerShape(1.dp)).background(
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.25f), Color.Transparent),
                ),
            ),
        )
        Text(
            text = title.uppercase(),
            color = NyoraTokens.onSurfaceMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun NavRow(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    isCompact: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalNyoraAccent.current.color
    var hovered by remember { mutableStateOf(false) }

    val rowBg: Brush = when {
        selected -> Brush.horizontalGradient(
            listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.12f), Color.Transparent),
        )
        hovered -> Brush.horizontalGradient(listOf(NyoraTokens.glass2, NyoraTokens.glass2))
        else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val iconTint = if (selected) accent else NyoraTokens.onSurfaceMuted
    val textColor = when {
        selected -> NyoraTokens.onSurfaceHigh
        hovered -> NyoraTokens.onSurfaceHigh
        else -> NyoraTokens.onSurfaceBody
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompact) 44.dp else 42.dp)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(rowBg)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isCompact) Arrangement.Center else Arrangement.Start,
    ) {
        if (isCompact) {
            Icon(icon, label, tint = iconTint, modifier = Modifier.size(22.dp))
        } else {
            Box(
                Modifier.padding(start = 3.dp).width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (selected) accent else Color.Transparent),
            )
            Spacer(Modifier.width(9.dp))
            Icon(icon, label, tint = iconTint, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(13.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}
