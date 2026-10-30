package app.novachat.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.novachat.calls.CallMember
import app.novachat.calls.MemberPhase
import app.novachat.data.User
import app.novachat.ui.common.Avatar
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/** One square in a group call: someone's video, or their photo while their camera is off. */
data class CallTile(
    val id: String,
    val name: String,
    val avatarId: String?,
    val video: VideoTrack?,
    val mirror: Boolean = false,
    val status: String? = null,
)

/** The tiles for a group call: you first, then everyone else in the order they were called. */
fun groupCallTiles(
    myId: String?,
    me: User?,
    members: List<CallMember>,
    people: Map<String, User>,
    videos: Map<String, VideoTrack>,
    localVideo: VideoTrack?,
    frontCamera: Boolean,
): List<CallTile> {
    val mine = CallTile(myId ?: "me", "You", me?.avatarId, localVideo, mirror = frontCamera)
    return listOf(mine) + members.map { m ->
        val person = people[m.id]
        CallTile(
            id = m.id,
            name = person?.displayName ?: "…",
            avatarId = person?.avatarId,
            video = videos[m.id]?.takeIf { m.video && m.phase != MemberPhase.Invited },
            status = when (m.phase) {
                MemberPhase.Invited -> "Ringing…"
                MemberPhase.Connecting -> "Connecting…"
                MemberPhase.Reconnecting -> "Reconnecting…"
                MemberPhase.Connected -> null
            },
        )
    }
}

/** Splits [count] tiles into rows: 1, 2 stacked, then two per row. */
fun gridRows(count: Int): List<Int> = when {
    count <= 0 -> emptyList()
    count <= 2 -> List(count) { 1 }
    else -> List(count / 2) { 2 } + if (count % 2 == 1) listOf(1) else emptyList()
}

@Composable
fun GroupCallGrid(tiles: List<CallTile>, egl: EglBase.Context, modifier: Modifier = Modifier) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var next = 0
        for (perRow in gridRows(tiles.size)) {
            val row = tiles.subList(next, next + perRow)
            next += perRow
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> Tile(tile, egl, Modifier.weight(1f).fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun Tile(tile: CallTile, egl: EglBase.Context, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f))) {
        val track = tile.video
        if (track != null) {
            VideoView(track, egl, mirror = tile.mirror, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(tile.name, tile.id, 72.dp, tile.avatarId)
                tile.status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            tile.name,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
