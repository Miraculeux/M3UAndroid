package com.m3u.data.repository.playlist

import android.content.Context
import android.net.Uri
import com.m3u.data.database.model.Channel
import com.m3u.data.repository.channel.ChannelRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

fun interface BundledIptvRepository {
    suspend fun load(): List<Channel>
}

internal class BundledIptvRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
) : BundledIptvRepository {
    private val loadMutex = Mutex()

    override suspend fun load(): List<Channel> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            val file = File(context.filesDir, "bundled-iptv.m3u")
            context.assets.open("iptv.m3u").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val url = Uri.fromFile(file).toString()
            playlistRepository.m3uOrThrow(title = "IPTV", url = url)
            channelRepository.getByPlaylistUrl(url)
                .sortedBy(Channel::id)
                .also { channels ->
                    check(channels.isNotEmpty()) { "The bundled IPTV playlist has no channels" }
                }
        }
    }
}
