package com.m3u.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.playlist.IptvQuality
import com.m3u.i18n.R.plurals
import com.m3u.i18n.R.string

@Composable
fun TvIptvChannelList(
    state: TvIptvUiState,
    listState: LazyGridState,
    onFocus: (Channel) -> Unit,
    onPlay: (Channel) -> Unit,
    onRetry: () -> Unit,
    onQuality: (IptvQuality) -> Unit,
    onTab: (TvIptvTab) -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val selectedTabFocusRequester = remember { FocusRequester() }
    val qualityFocusRequester = remember { FocusRequester() }
    var channelGridHasFocus by remember { mutableStateOf(false) }
    val inputModeManager = LocalInputModeManager.current
    val initialChannelId = remember(state.channels) {
        state.channels.firstOrNull { it.id == state.focusedChannelId }?.id
            ?: state.channels.firstOrNull()?.id
    }
    BackHandler(enabled = channelGridHasFocus && state.channelNumberInput.isEmpty()) {
        selectedTabFocusRequester.requestFocus()
    }
    LaunchedEffect(state.channels, state.loading, state.loadFailed) {
        if (state.loading) {
            return@LaunchedEffect
        }
        if (!state.loadFailed && state.channels.isNotEmpty()) {
            val index = state.channels.indexOfFirst { it.id == initialChannelId }
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
            }
        }
        repeat(2) { withFrameNanos { } }
        inputModeManager.requestInputMode(InputMode.Keyboard)
        if (!state.loadFailed && state.channels.isEmpty()) {
            selectedTabFocusRequester.requestFocus()
        } else {
            initialFocusRequester.requestFocus()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f)) {
                SectionTitle(
                    title = stringResource(string.tv_iptv_title),
                    subtitle = pluralStringResource(
                        plurals.tv_channel_count,
                        state.channels.size,
                        state.channels.size,
                    ),
                )
            }
            TvActionButton(
                text = stringResource(
                    when (state.quality) {
                        IptvQuality.HD -> string.tv_iptv_quality_1080p
                        IptvQuality.UHD_LOW -> string.tv_iptv_quality_4k_low
                        IptvQuality.UHD_HIGH -> string.tv_iptv_quality_4k_high
                    }
                ),
                icon = Icons.Rounded.Tv,
                onClick = { onQuality(state.quality.next()) },
                enabled = !state.loading,
                focusRequester = qualityFocusRequester,
                modifier = Modifier.focusProperties { down = selectedTabFocusRequester },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            TvIptvTab.entries.forEach { tab ->
                val label = stringResource(
                    when (tab) {
                        TvIptvTab.Favorite -> string.tv_iptv_tab_favorite
                        TvIptvTab.All -> string.tv_iptv_tab_all
                    }
                )
                val selected = tab == state.selectedTab
                FocusFrame(
                    onClick = { onTab(tab) },
                    selected = selected,
                    selectionState = selected,
                    enabled = !state.loading,
                    focusRequester = selectedTabFocusRequester.takeIf { selected },
                    modifier = Modifier.focusProperties {
                        up = qualityFocusRequester
                        if (state.channels.isNotEmpty() || state.loadFailed) {
                            down = initialFocusRequester
                        }
                    },
                    semanticRole = Role.Tab,
                    semanticsLabel = label,
                    focusedScale = 1f,
                ) { focused ->
                    Text(
                        text = label,
                        color = if (focused || selected) TvColors.OnFocus else TvColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(string.ui_state_loading),
                    color = TvColors.TextSecondary,
                    fontSize = 24.sp,
                )
            }
            state.loadFailed -> {
                val message = stringResource(string.tv_iptv_load_failed)
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.semantics {
                        error(message)
                        liveRegion = LiveRegionMode.Polite
                    },
                ) {
                    Text(text = message, color = TvColors.Danger, fontSize = 24.sp)
                    TvActionButton(
                        text = stringResource(string.ui_action_retry),
                        icon = Icons.Rounded.Refresh,
                        onClick = onRetry,
                        focusRequester = initialFocusRequester,
                    )
                }
            }
            state.channels.isEmpty() -> Text(
                text = stringResource(string.tv_iptv_tab_empty),
                color = TvColors.TextSecondary,
                fontSize = 24.sp,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { channelGridHasFocus = it.hasFocus }
                    .focusGroup(),
            ) {
                itemsIndexed(state.channels, key = { _, channel -> channel.id }) { index, channel ->
                    val label = stringResource(
                        string.tv_iptv_channel_label,
                        index + 1,
                        channel.title,
                        channel.category,
                    )
                    FocusFrame(
                        onClick = { onPlay(channel) },
                        onFocus = { onFocus(channel) },
                        focusRequester = initialFocusRequester.takeIf { channel.id == initialChannelId },
                        semanticsLabel = label,
                        focusedScale = 1.01f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 88.dp)
                            .focusProperties {
                                if (index < 4) up = selectedTabFocusRequester
                            },
                    ) { focused ->
                        val textColor = if (focused) TvColors.OnFocus else TvColors.TextPrimary
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    color = textColor,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = channel.title,
                                    color = textColor,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                text = channel.category,
                                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
