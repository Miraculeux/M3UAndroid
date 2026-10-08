package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.m3u.i18n.R.string

@Composable
fun App(viewModel: TvIptvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackException by viewModel.playbackException.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (state.playingChannel != null) {
            TvPlayerScreen(
                player = player,
                channel = state.playingChannel,
                isPlaying = isPlaying,
                playbackState = playbackState,
                errorMessage = if (state.playbackStartFailed || playbackException != null) {
                    stringResource(string.tv_iptv_playback_failed)
                } else {
                    null
                },
                onPlayPause = { viewModel.pauseOrContinue(!isPlaying) },
                onBack = viewModel::closePlayer,
                onClose = viewModel::closePlayer,
            )
        } else {
            TvIptvChannelList(
                state = state,
                listState = listState,
                onFocus = viewModel::focusChannel,
                onPlay = viewModel::play,
                onRetry = viewModel::loadPlaylist,
            )
        }
    }
}
