package org.listenbrainz.android.repository.brainzplayer

import kotlinx.coroutines.flow.Flow
import org.listenbrainz.android.model.Song
import org.listenbrainz.android.model.SongEntity

interface SongRepository {
    fun getSongsStream() : Flow<List<Song>>
    fun getRecentlyPlayedSongs() : Flow<List<Song>>
    fun getSongsPlayedToday() : Flow<List<Song>>
    fun getSongsPlayedThisWeek() : Flow<List<Song>>
    suspend fun addSongs(userRequestedRefresh: Boolean = false): Boolean
    suspend fun updateSong(song : Song)

}