package com.metrolist.music.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.metrolist.music.desktop.db.DatabaseHelper
import com.metrolist.music.desktop.db.Playlist
import com.metrolist.music.desktop.playback.SongInfo

@Composable
fun PlaylistPickerDialog(
    song: SongInfo,
    playlists: List<Playlist>,
    onDismiss: () -> Unit
) {
    // Check duplicates for each playlist
    val duplicateMap = remember(song.id, playlists) {
        playlists.associate { it.id to DatabaseHelper.isSongInPlaylist(it.id, song.id) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Playlist") },
        text = {
            if (playlists.isEmpty()) {
                Text("No playlists available")
            } else {
                Column {
                    playlists.forEach { playlist ->
                        val alreadyIn = duplicateMap[playlist.id] == true
                        ListItem(
                            headlineContent = {
                                Text(
                                    playlist.name,
                                    color = if (alreadyIn)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Text(
                                    if (alreadyIn) "Already added"
                                    else "${playlist.remoteSongCount ?: 0} songs"
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (alreadyIn) Icons.Default.Check
                                    else Icons.AutoMirrored.Filled.PlaylistAdd,
                                    null,
                                    tint = if (alreadyIn)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.clickable(enabled = !alreadyIn) {
                                // Ensure song exists in DB
                                DatabaseHelper.insertSong(
                                    id = song.id,
                                    title = song.title,
                                    thumbnailUrl = song.thumbnailUrl,
                                    albumName = song.album
                                )
                                val position = DatabaseHelper.getPlaylistSongCount(playlist.id)
                                DatabaseHelper.addSongToPlaylist(playlist.id, song.id, position)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
