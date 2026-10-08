package com.m3u.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.i18n.R.plurals
import com.m3u.i18n.R.string

@Composable
fun TvIptvChannelList(
    state: TvIptvUiState,
    listState: LazyListState,
    onFocus: (Channel) -> Unit,
    onPlay: (Channel) -> Unit,
    onRetry: () -> Unit,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val initialChannelId = remember(state.channels) {
        state.channels.firstOrNull { it.id == state.focusedChannelId }?.id
            ?: state.channels.firstOrNull()?.id
    }
    LaunchedEffect(state.channels, state.loading, state.loadFailed) {
        if (state.loadFailed) {
            repeat(2) { withFrameNanos { } }
            initialFocusRequester.requestFocus()
        } else if (!state.loading && state.channels.isNotEmpty()) {
            val index = state.channels.indexOfFirst { it.id == initialChannelId }
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                listState.scrollToItem(index)
            }
            repeat(2) { withFrameNanos { } }
            initialFocusRequester.requestFocus()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        SectionTitle(
            title = stringResource(string.tv_iptv_title),
            subtitle = pluralStringResource(
                plurals.tv_channel_count,
                state.channels.size,
                state.channels.size,
            ),
        )
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
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
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
                            .heightIn(min = 72.dp),
                    ) { focused ->
                        val textColor = if (focused) TvColors.OnFocus else TvColors.TextPrimary
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                color = textColor,
                                fontSize = 20.sp,
                                modifier = Modifier.width(56.dp),
                            )
                            Text(
                                text = channel.title,
                                color = textColor,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = channel.category,
                                color = textColor,
                                fontSize = 18.sp,
                                maxLines = 2,
                                modifier = Modifier.width(144.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
