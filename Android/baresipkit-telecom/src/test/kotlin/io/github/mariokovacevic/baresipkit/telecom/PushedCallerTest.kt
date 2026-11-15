package io.github.mariokovacevic.baresipkit.telecom

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PushedCallerTest {
    private fun pushed() = BaresipTelecomCall(UUID.randomUUID(), "sip:door@example.com", "Front door", hasVideo = true, isOutgoing = false)

    private fun announcedFor(
        push: String?,
        invite: String,
        uuid: UUID? = null,
        inviteUuid: UUID? = null,
    ): PushCallLedger.Resolution.Announced {
        val ledger = PushCallLedger(matchesPushWithoutCaller = true)
        ledger.add(PushCallLedger.Announcement(uuid ?: UUID.randomUUID(), push, hasVideo = false, namedByServer = uuid != null), 0)
        return ledger.resolve(invite, inviteUuid, 0) as PushCallLedger.Resolution.Announced
    }

    @Test
    fun theInvitesDisplayNameReplacesThePushedName() {
        val call = pushed()
        call.adoptInvite("sip:door@example.com", "Gate", callerConfirmed = true)
        assertEquals("Gate", call.displayName)
        assertEquals("sip:door@example.com", call.remoteUri)
    }

    @Test
    fun aConfirmedCallerKeepsThePushedNameWhenTheInviteHasNone() {
        val call = pushed()
        call.adoptInvite("sip:door@example.com", "", callerConfirmed = true)
        assertEquals("Front door", call.displayName)
    }

    @Test
    fun anUnconfirmedCallerShowsTheInvitesAddress() {
        val call = pushed()
        call.adoptInvite("sip:mallory@example.org", "", callerConfirmed = false)
        assertEquals("sip:mallory@example.org", call.displayName)
        assertEquals("sip:mallory@example.org", call.remoteUri)
    }

    @Test
    fun onlyTheUuidOrTheFullCallerConfirmsAPush() {
        assertEquals(false, announcedFor(push = null, invite = "sip:mallory@example.org").callerConfirmed)
        assertEquals(false, announcedFor(push = "door", invite = "sip:door@example.org").callerConfirmed)
        assertEquals(true, announcedFor(push = "sip:door@example.com", invite = "sip:door@example.com").callerConfirmed)
        val uuid = UUID.randomUUID()
        assertEquals(true, announcedFor(push = null, invite = "sip:door@example.com", uuid = uuid, inviteUuid = uuid).callerConfirmed)
    }
}
