package com.m3u.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.m3u.i18n.R.string

@Composable
fun App(viewModel: TvIptvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val playbackException by viewModel.playbackException.collectAsStateWithLifecycle()
    val listState = rememberLazyGridState()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.cancelChannelNumber()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { event ->
                val inputState = viewModel.state.value
                val keyCode = event.nativeKeyEvent.keyCode
                val digit = when (keyCode) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 ->
                        keyCode - AndroidKeyEvent.KEYCODE_0
                    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
                        keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0
                    else -> null
                }
                val selectingNumber = inputState.channelNumberInput.isNotEmpty()
                when {
                    digit != null && !inputState.loading && !inputState.loadFailed && inputState.channels.isNotEmpty() -> {
                        if (event.type == KeyEventType.KeyUp) viewModel.enterChannelDigit(digit)
                        true
                    }
                    selectingNumber && event.key == Key.Back -> {
                        if (event.type == KeyEventType.KeyUp) viewModel.cancelChannelNumber()
                        true
                    }
                    selectingNumber && (
                        event.key == Key.DirectionCenter || event.key == Key.Enter ||
                            event.key == Key.NumPadEnter
                        ) -> {
                        if (event.type == KeyEventType.KeyUp) viewModel.confirmChannelNumber()
                        true
                    }
                    else -> {
                        if (selectingNumber && event.type == KeyEventType.KeyDown) {
                            viewModel.cancelChannelNumber()
                        }
                        false
                    }
                }
            },
    ) {
        if (state.loading && state.channels.isEmpty()) {
            Text(
                text = stringResource(string.ui_state_loading),
                color = TvColors.TextSecondary,
                fontSize = 24.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (state.playingChannel != null) {
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
                onPreviousChannel = viewModel::previousChannel,
                onNextChannel = viewModel::nextChannel,
            )
        } else {
            TvIptvChannelList(
                state = state,
                listState = listState,
                onFocus = viewModel::focusChannel,
                onPlay = viewModel::play,
                onRetry = viewModel::loadPlaylist,
                onPrefer4k = viewModel::setPrefer4k,
            )
        }
        BackHandler(enabled = state.channelNumberInput.isNotEmpty()) {
            viewModel.cancelChannelNumber()
        }
        if (state.channelNumberInput.isNotEmpty() || state.rejectedChannelNumber != null) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .background(TvColors.Background.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = state.rejectedChannelNumber?.let {
                        stringResource(string.tv_iptv_number_invalid, it)
                    } ?: stringResource(string.tv_iptv_number_input, state.channelNumberInput),
                    color = if (state.rejectedChannelNumber != null) TvColors.Danger else TvColors.TextPrimary,
                    fontSize = 28.sp,
                )
                if (state.channelNumberInput.isNotEmpty()) {
                    Text(
                        text = stringResource(string.tv_iptv_number_hint),
                        color = TvColors.TextSecondary,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }
}
