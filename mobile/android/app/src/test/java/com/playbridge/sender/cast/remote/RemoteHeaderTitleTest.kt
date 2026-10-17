package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.remote.ui.remoteConnectionTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteHeaderTitleTest {
    @Test fun headerNamesTheActivityAndKeepsReconnectAndExternal() {
        assertEquals("Reconnecting…", remoteConnectionTitle(connected(RemoteContext.Player).copy(connection = RemoteConnection.Reconnecting(1, 3, 2))))
        assertEquals("Reconnecting…", remoteConnectionTitle(connected(RemoteContext.Browser, profile = RemoteProfile.External).copy(connection = RemoteConnection.Connecting("Living Room"))))
        assertEquals("Casting to Living Room", remoteConnectionTitle(connected(RemoteContext.Player, profile = RemoteProfile.External)))
        assertEquals("Watching on Living Room", remoteConnectionTitle(connected(RemoteContext.Player, RemoteMediaKind.Video)))
        assertEquals("Watching on Living Room", remoteConnectionTitle(connected(RemoteContext.Player).copy(playback = null)))
        assertEquals("Listening on Living Room", remoteConnectionTitle(connected(RemoteContext.Player, RemoteMediaKind.Audio)))
        assertEquals("Viewing on Living Room", remoteConnectionTitle(connected(RemoteContext.Player, RemoteMediaKind.Image)))
        assertEquals("Browsing on Living Room", remoteConnectionTitle(connected(RemoteContext.Browser, engine = RemoteBrowserEngine.WebView)))
        assertEquals("Browsing on Living Room", remoteConnectionTitle(connected(RemoteContext.Browser, engine = RemoteBrowserEngine.Gecko)))
        assertEquals("Connected to Living Room", remoteConnectionTitle(connected(RemoteContext.Idle)))
        assertEquals("Connected to Living Room", remoteConnectionTitle(connected(RemoteContext.ScreenMirror)))
        assertEquals("Connected to Living Room", remoteConnectionTitle(connected(RemoteContext.Unknown)))
        assertEquals("Connected to TV", remoteConnectionTitle(connected(RemoteContext.Idle).copy(receiver = null)))
    }

    @Test fun pairedTvKeepsItsNameWithoutTheReceiver() {
        val receiverDown = connected(RemoteContext.Player).copy(connection = RemoteConnection.Reconnecting(1, 3, 2))
        assertEquals("Remote · Living Room", remoteConnectionTitle(receiverDown.copy(fullControl = FullControlStatus(connected = true, paired = true))))
        assertEquals("Remote · Living Room", remoteConnectionTitle(receiverDown.copy(fullControl = FullControlStatus(paired = true))))
    }

    private fun connected(
        context: RemoteContext,
        kind: RemoteMediaKind = RemoteMediaKind.Video,
        profile: RemoteProfile = RemoteProfile.AndroidTv,
        engine: RemoteBrowserEngine = RemoteBrowserEngine.Unknown,
    ) = RemoteUiState(
        receiver = RemoteReceiver(remoteTestEndpoint, "Living Room", profile),
        connection = RemoteConnection.Connected("Living Room"),
        context = context,
        playback = RemotePlayback(mediaKind = kind),
        browser = if (context == RemoteContext.Browser) RemoteBrowserState(engine) else null,
    )
}
