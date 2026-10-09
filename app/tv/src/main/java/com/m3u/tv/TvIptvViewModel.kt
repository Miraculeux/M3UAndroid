package com.m3u.tv

import android.database.sqlite.SQLiteException
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.playlist.BundledIptvRepository
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class TvIptvUiState(
    val channels: List<Channel> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val focusedChannelId: Int? = null,
    val playingChannel: Channel? = null,
    val playbackStartFailed: Boolean = false,
) {
    fun openPlayer(channel: Channel): TvIptvUiState = copy(
        focusedChannelId = channel.id,
        playingChannel = channel,
        playbackStartFailed = false,
    )

    fun closePlayer(): TvIptvUiState = copy(
        playingChannel = null,
        playbackStartFailed = false,
    )
}

@HiltViewModel
class TvIptvViewModel @Inject constructor(
    private val bundledIptvRepository: BundledIptvRepository,
    private val playerManager: PlayerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(TvIptvUiState())
    val state = _state.asStateFlow()
    val player = playerManager.player
    val isPlaying = playerManager.isPlaying
    val playbackState = playerManager.playbackState
    val playbackException = playerManager.playbackException
    private var loadJob: Job? = null
    private var playJob: Job? = null

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, loadFailed = false) }
            try {
                val channels = bundledIptvRepository.load()
                _state.update { it.copy(channels = channels, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                reportLoadFailure(error)
            } catch (error: IllegalArgumentException) {
                reportLoadFailure(error)
            } catch (error: IllegalStateException) {
                reportLoadFailure(error)
            } catch (error: SQLiteException) {
                reportLoadFailure(error)
            }
        }
    }

    fun focusChannel(channel: Channel) {
        _state.update { it.copy(focusedChannelId = channel.id) }
    }

    fun play(channel: Channel) {
        playJob?.cancel()
        _state.update { it.openPlayer(channel) }
        playJob = viewModelScope.launch {
            try {
                playerManager.play(MediaCommand.Common(channel.id), applyContinueWatching = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                reportPlaybackFailure(error)
            } catch (error: IllegalArgumentException) {
                reportPlaybackFailure(error)
            } catch (error: IllegalStateException) {
                reportPlaybackFailure(error)
            } catch (error: SQLiteException) {
                reportPlaybackFailure(error)
            }
        }
    }

    fun previousChannel() {
        switchChannel(-1)
    }

    fun nextChannel() {
        switchChannel(1)
    }

    private fun switchChannel(offset: Int) {
        val state = _state.value
        val playingChannel = state.playingChannel ?: return
        val index = state.channels.indexOfFirst { it.id == playingChannel.id }
        check(index >= 0) { "The playing IPTV channel is missing from the channel list" }
        val nextIndex = (index + offset + state.channels.size) % state.channels.size
        play(state.channels[nextIndex])
    }

    fun pauseOrContinue(continuePlayback: Boolean) {
        playerManager.pauseOrContinue(continuePlayback)
    }

    fun closePlayer() {
        playJob?.cancel()
        playerManager.release()
        _state.update(TvIptvUiState::closePlayer)
    }

    override fun onCleared() {
        playJob?.cancel()
        playerManager.release()
        super.onCleared()
    }

    private fun reportLoadFailure(error: Exception) {
        Timber.tag("TvIptvViewModel").e(error, "Unable to load the bundled IPTV playlist")
        _state.update { it.copy(loading = false, loadFailed = true) }
    }

    private fun reportPlaybackFailure(error: Exception) {
        Timber.tag("TvIptvViewModel").e(error, "Unable to start IPTV playback")
        _state.update { it.copy(playbackStartFailed = true) }
    }
}
