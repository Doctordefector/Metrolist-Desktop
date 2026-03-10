package com.metrolist.music.desktop.sync

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.desktop.auth.AuthManager
import com.metrolist.music.desktop.db.DatabaseHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.LocalDateTime

data class SyncState(
    val isSyncing: Boolean = false,
    val progress: String = "",
    val lastSyncTime: String? = null,
    val error: String? = null
)

object LibrarySync {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var syncJob: Job? = null

    fun syncLibrary() {
        if (_syncState.value.isSyncing) return

        val authState = AuthManager.authState.value
        if (!authState.isLoggedIn) {
            _syncState.value = SyncState(error = "Not logged in")
            return
        }

        syncJob = scope.launch {
            _syncState.value = SyncState(isSyncing = true, progress = "Syncing library...")

            try {
                // Phase 1: Fetch all data from YouTube API (network-bound)
                // Fetch liked songs (requires pagination) and other library types in parallel
                val songsDeferred = async { fetchAllLikedSongs() }
                val playlistsDeferred = async { fetchLibraryPlaylists() }
                val albumsDeferred = async { fetchLibraryAlbums() }
                val artistsDeferred = async { fetchLibraryArtists() }

                _syncState.update { it.copy(progress = "Fetching library from YouTube...") }

                val songs = songsDeferred.await()
                val playlists = playlistsDeferred.await()
                val albums = albumsDeferred.await()
                val artists = artistsDeferred.await()

                _syncState.update {
                    it.copy(progress = "Saving ${songs.size} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists...")
                }

                // Phase 2: Write everything to DB in a single transaction
                // This means: one disk sync, one flow notification, zero UI flicker
                DatabaseHelper.transaction {
                    val now = LocalDateTime.now().toString()

                    songs.forEach { song ->
                        saveSongToDatabase(song, liked = true, now = now)
                    }

                    albums.forEach { album ->
                        saveAlbumToDatabase(album, bookmarked = true, now = now)
                    }

                    artists.forEach { artist ->
                        saveArtistToDatabase(artist, subscribed = true, now = now)
                    }

                    playlists.forEach { playlist ->
                        savePlaylistToDatabase(playlist, now = now)
                    }
                }

                _syncState.value = SyncState(
                    isSyncing = false,
                    lastSyncTime = LocalDateTime.now().toString(),
                    progress = "Synced ${songs.size} songs, ${albums.size} albums, ${artists.size} artists, ${playlists.size} playlists"
                )

                // Clear progress message after delay
                delay(5000)
                _syncState.update { it.copy(progress = "") }

            } catch (e: CancellationException) {
                _syncState.value = SyncState(progress = "Sync cancelled")
            } catch (e: Exception) {
                Timber.e("Sync failed: ${e.message}")
                _syncState.value = SyncState(
                    isSyncing = false,
                    error = "Sync failed: ${e.message}"
                )
            }
        }
    }

    fun cancelSync() {
        syncJob?.cancel()
        _syncState.value = SyncState(progress = "Sync cancelled")
    }

    // ============ Network Fetch (no DB writes) ============

    private suspend fun fetchAllLikedSongs(): List<SongItem> {
        val allSongs = mutableListOf<SongItem>()

        try {
            val playlistResult = YouTube.playlist("LM")
            val playlist = playlistResult.getOrNull() ?: return emptyList()

            allSongs.addAll(playlist.songs)
            _syncState.update { it.copy(progress = "Fetching liked songs... (${allSongs.size})") }

            // Paginate through all continuation pages
            var continuation = playlist.songsContinuation ?: playlist.continuation
            var pageCount = 1

            while (continuation != null && pageCount < 200) {
                val contResult = YouTube.playlistContinuation(continuation)
                val contPlaylist = contResult.getOrNull() ?: break

                allSongs.addAll(contPlaylist.songs)
                continuation = contPlaylist.continuation
                pageCount++
                _syncState.update { it.copy(progress = "Fetching liked songs... (${allSongs.size})") }
            }
        } catch (e: Exception) {
            Timber.e("Error fetching liked songs: ${e.message}")
        }

        return allSongs
    }

    private suspend fun fetchLibraryPlaylists(): List<PlaylistItem> {
        return try {
            val result = YouTube.library("FEmusic_liked_playlists")
            result.getOrNull()?.items?.filterIsInstance<PlaylistItem>() ?: emptyList()
        } catch (e: Exception) {
            Timber.e("Error fetching playlists: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchLibraryAlbums(): List<AlbumItem> {
        return try {
            val result = YouTube.library("FEmusic_liked_albums")
            result.getOrNull()?.items?.filterIsInstance<AlbumItem>() ?: emptyList()
        } catch (e: Exception) {
            Timber.e("Error fetching albums: ${e.message}")
            emptyList()
        }
    }

    private suspend fun fetchLibraryArtists(): List<ArtistItem> {
        return try {
            val result = YouTube.library("FEmusic_library_corpus_track_artists")
            result.getOrNull()?.items?.filterIsInstance<ArtistItem>() ?: emptyList()
        } catch (e: Exception) {
            Timber.e("Error fetching artists: ${e.message}")
            emptyList()
        }
    }

    // ============ DB Writes (called inside transaction) ============

    private fun saveSongToDatabase(song: SongItem, liked: Boolean = false, now: String) {
        DatabaseHelper.insertSong(
            id = song.id,
            title = song.title,
            duration = song.duration ?: -1,
            thumbnailUrl = song.thumbnail,
            albumId = song.album?.id,
            albumName = song.album?.name,
            explicit = song.explicit,
            liked = liked,
            likedDate = if (liked) now else null,
            inLibrary = now
        )

        song.artists.forEachIndexed { index, artist ->
            val artistId = artist.id ?: "unknown_${artist.name.hashCode()}"
            DatabaseHelper.insertArtist(
                id = artistId,
                name = artist.name
            )
            DatabaseHelper.insertSongArtistMap(
                songId = song.id,
                artistId = artistId,
                position = index
            )
        }
    }

    private fun saveAlbumToDatabase(album: AlbumItem, bookmarked: Boolean = false, now: String) {
        DatabaseHelper.insertAlbum(
            id = album.id,
            title = album.title,
            playlistId = album.playlistId,
            year = album.year,
            thumbnailUrl = album.thumbnail,
            explicit = album.explicit,
            bookmarkedAt = if (bookmarked) now else null,
            inLibrary = now
        )
    }

    private fun saveArtistToDatabase(artist: ArtistItem, subscribed: Boolean = false, now: String) {
        DatabaseHelper.insertArtist(
            id = artist.id,
            name = artist.title,
            thumbnailUrl = artist.thumbnail,
            channelId = artist.channelId,
            bookmarkedAt = if (subscribed) now else null
        )
    }

    private fun savePlaylistToDatabase(playlist: PlaylistItem, now: String) {
        val songCount = playlist.songCountText?.filter { it.isDigit() }?.toIntOrNull()

        DatabaseHelper.insertPlaylist(
            id = playlist.id,
            name = playlist.title,
            browseId = playlist.id,
            isEditable = playlist.isEditable,
            bookmarkedAt = now,
            remoteSongCount = songCount,
            thumbnailUrl = playlist.thumbnail
        )
    }

    fun clearError() {
        _syncState.update { it.copy(error = null) }
    }
}
