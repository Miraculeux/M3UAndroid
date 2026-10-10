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
import kotlinx.coroutines.delay
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
    val prefer4k: Boolean = false,
    val focusedChannelId: Int? = null,
    val playingChannel: Channel? = null,
    val playbackStartFailed: Boolean = false,
    val channelNumberInput: String = "",
    val rejectedChannelNumber: String? = null,
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
    private var numberSelectionJob: Job? = null

    init {
        loadPlaylist()
    }

    fun loadPlaylist() {
        loadPlaylist(null)
    }

    fun setPrefer4k(value: Boolean) {
        val state = _state.value
        if (state.loading || state.playingChannel != null || state.prefer4k == value) return
        loadPlaylist(value)
    }

    private fun loadPlaylist(requestedPrefer4k: Boolean?) {
        cancelChannelNumber()
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, loadFailed = false) }
            try {
                val prefer4k = requestedPrefer4k ?: bundledIptvRepository.getPrefer4k()
                val previousTitle = _state.value.channels
                    .firstOrNull { it.id == _state.value.focusedChannelId }?.title
                val channels = bundledIptvRepository.load(prefer4k)
                if (requestedPrefer4k != null) bundledIptvRepository.setPrefer4k(prefer4k)
                _state.update {
                    it.copy(
                        channels = channels,
                        loading = false,
                        prefer4k = prefer4k,
                        focusedChannelId = channels.firstOrNull { channel -> channel.title == previousTitle }?.id,
                    )
                }
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
        cancelChannelNumber()
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
        cancelChannelNumber()
        switchChannel(-1)
    }

    fun nextChannel() {
        cancelChannelNumber()
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
        cancelChannelNumber()
        playJob?.cancel()
        playerManager.release()
        _state.update(TvIptvUiState::closePlayer)
    }

    fun enterChannelDigit(digit: Int) {
        require(digit in 0..9) { "A channel digit must be between 0 and 9" }
        val state = _state.value
        check(!state.loading && state.channels.isNotEmpty()) { "The IPTV channel list is not ready" }
        numberSelectionJob?.cancel()
        val number = (state.channelNumberInput + digit).trimStart('0').ifEmpty { "0" }
        if (number.length > state.channels.size.toString().length) {
            rejectChannelNumber(number)
            return
        }
        _state.update { it.copy(channelNumberInput = number, rejectedChannelNumber = null) }
        numberSelectionJob = viewModelScope.launch {
            delay(1_500)
            numberSelectionJob = null
            confirmChannelNumber()
        }
    }

    fun confirmChannelNumber() {
        val state = _state.value
        if (state.channelNumberInput.isEmpty()) return
        numberSelectionJob?.cancel()
        numberSelectionJob = null
        val channel = state.channelNumberInput.toIntOrNull()?.let { state.channels.getOrNull(it - 1) }
        if (channel == null) {
            rejectChannelNumber(state.channelNumberInput)
        } else {
            play(channel)
        }
    }

    fun cancelChannelNumber() {
        numberSelectionJob?.cancel()
        numberSelectionJob = null
        _state.update { it.copy(channelNumberInput = "", rejectedChannelNumber = null) }
    }

    private fun rejectChannelNumber(number: String) {
        Timber.tag("TvIptvViewModel").i("Unknown IPTV channel number: %s", number)
        _state.update { it.copy(channelNumberInput = "", rejectedChannelNumber = number) }
        numberSelectionJob = viewModelScope.launch {
            delay(3_000)
            _state.update { it.copy(rejectedChannelNumber = null) }
            numberSelectionJob = null
        }
    }

    override fun onCleared() {
        numberSelectionJob?.cancel()
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
