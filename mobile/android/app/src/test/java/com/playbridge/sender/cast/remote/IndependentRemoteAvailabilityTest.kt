package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentRemoteAvailabilityTest {
    @Test fun statesAndPickerWordsMatchThePhonePolicy() {
        data class Fixture(val receiver: Boolean, val tv: Boolean, val paired: Boolean, val ambiguous: Boolean, val state: RemoteAvailability, val status: String)
        val fixtures = listOf(
            Fixture(true,true,true,false,RemoteAvailability.Full,"PlayBridge · Full control"),
            Fixture(false,true,true,false,RemoteAvailability.TvRemoteOnly,"TV remote only"),
            Fixture(false,false,true,false,RemoteAvailability.Offline,"Offline"),
            Fixture(false,false,false,false,RemoteAvailability.Unpaired,"Offline"),
            Fixture(true,false,true,false,RemoteAvailability.Receiver,"PlayBridge"),
            Fixture(true,true,false,false,RemoteAvailability.Receiver,"PlayBridge"),
            Fixture(false,true,false,false,RemoteAvailability.Unpaired,"Offline"),
            Fixture(false,true,true,true,RemoteAvailability.Offline,"Offline"),
            Fixture(true,true,true,true,RemoteAvailability.Receiver,"PlayBridge"),
        )
        fixtures.forEach {
            assertEquals(it.state, RemoteAvailability.resolve(it.receiver,it.tv,it.paired,it.ambiguous))
            assertEquals(it.status, RemoteAvailability.pickerStatus(it.receiver,it.tv,it.paired,it.ambiguous))
        }
        val receiverControls = setOf("nowPlaying","seek","transport","queue","tracks","receiverInput")
        val tvControls = setOf("pad","back","home","keyboard","volume","mute","tvMenu")
        assertTrue(RemoteAvailability.Full.visibleControls.containsAll(receiverControls + tvControls))
        assertTrue(RemoteAvailability.TvRemoteOnly.visibleControls.intersect(receiverControls).isEmpty())
        assertTrue(RemoteAvailability.TvRemoteOnly.visibleControls.containsAll(tvControls + "openPlayBridge"))
        assertEquals(setOf("header","picker","retry","switchTV"), RemoteAvailability.Offline.visibleControls)
        assertFalse(RemoteAvailability.Unpaired.tvControls)
    }
    @Test fun selectingAnOfflineTvNeverSwitchesToAnotherReachableTv() {
        val a = RemoteTVChoice("playbridge:a", true, true)
        val b = RemoteTVChoice("playbridge:b", true, true)
        assertEquals(RemoteTVSelection.Picker, RemoteTVSelection.resolve(null, emptyList()))
        assertEquals(RemoteTVSelection.Selected(a.endpoint), RemoteTVSelection.resolve(null, listOf(a)))
        assertEquals(RemoteTVSelection.Selected(a.endpoint), RemoteTVSelection.resolve(null, listOf(a,a)))
        assertEquals(RemoteTVSelection.Picker, RemoteTVSelection.resolve(null, listOf(a,b)))
        assertEquals(RemoteTVSelection.Selected("playbridge:offline"), RemoteTVSelection.resolve("playbridge:offline", listOf(a,b)))
        assertEquals(RemoteTVSelection.Picker, RemoteTVSelection.resolve(null, listOf(a.copy(ambiguous = true))))
        assertEquals(RemoteTVSelection.Picker, RemoteTVSelection.resolve(null, listOf(a.copy(paired = false))))
    }
    @Test fun stalePlaybackAndBrowserControlsDisappearForTvRemoteOnly() {
        val state = stage1PolicyState("player", external = false, remote = true, connected = false,
            seekable = true, indices = listOf(0,1), currentIndex = 0).let {
            it.copy(fullControl = FullControlStatus(connected = true, paired = true, keys = setOf(RemoteKey.Up, RemoteKey.Ok)))
        }
        val layout = resolveRemoteLayout(state, RemoteViewport())
        assertFalse(layout.info)
        assertFalse(layout.wave)
        assertFalse(layout.showQueue)
        assertFalse(layout.browserRow)
        assertFalse(layout.tracks.audio || layout.tracks.subtitles || layout.tracks.more)
        assertEquals(RemoteInputPresentation.Full, layout.inputPresentation)
    }
}
