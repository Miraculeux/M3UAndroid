package com.m3u.data.repository.playlist

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.m3u.core.foundation.architecture.preferences.Settings
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.channel.ChannelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface BundledIptvRepository {
    suspend fun load(prefer4k: Boolean): List<Channel>
    suspend fun getPrefer4k(): Boolean
    suspend fun setPrefer4k(value: Boolean)
    suspend fun getLastChannelTitle(): String?
    suspend fun setLastChannelTitle(value: String)
}

internal class BundledIptvRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val settings: Settings,
) : BundledIptvRepository {
    private val loadMutex = Mutex()
    private val prefer4kKey = booleanPreferencesKey("tv_iptv_prefer_4k")
    private val lastChannelKey = stringPreferencesKey("tv_iptv_last_channel_title")

    override suspend fun getPrefer4k(): Boolean = settings.data.first()[prefer4kKey] ?: false

    override suspend fun setPrefer4k(value: Boolean) {
        settings.edit { it[prefer4kKey] = value }
    }

    override suspend fun getLastChannelTitle(): String? = settings.data.first()[lastChannelKey]

    override suspend fun setLastChannelTitle(value: String) {
        settings.edit { it[lastChannelKey] = value }
    }

    override suspend fun load(prefer4k: Boolean): List<Channel> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            val file = File(context.filesDir, "bundled-iptv.m3u")
            context.assets.open(if (prefer4k) "iptv-4k.m3u" else "iptv.m3u").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val url = Uri.fromFile(file).toString()
            playlistRepository.m3uOrThrow(title = "IPTV", url = url)
            val channelsByUrl = channelRepository.getByPlaylistUrl(url).associateBy(Channel::url)
            // Database IDs can survive imports; channel numbers follow the bundled file instead.
            file.useLines { lines ->
                lines.map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { channelUrl ->
                        checkNotNull(channelsByUrl[channelUrl]) {
                            "A bundled IPTV channel was not imported: $channelUrl"
                        }
                    }
                    .toList()
            }
                .also { channels ->
                    check(channels.isNotEmpty()) { "The bundled IPTV playlist has no channels" }
                }
        }
    }
}
