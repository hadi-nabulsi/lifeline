package app.getknit.knit.mesh.protocol

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.mesh.crypto.MessageCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group frames the iOS port emits (`vectors/ios-emitted-v1.json`): Carol's v1 group chat to Alice and Bob,
 * her rename and her leave, and the capabilities her profile carries. A repro for the interop audit
 * (`test/interop-audit`), standing in for knit-ios's planned A5: each frame must verify, re-encode to the bytes
 * Carol signed, name the group whose id derives from its roster the way `InboundPipeline.vetRoster` checks it,
 * and open for both recipients under the header `InboundPipeline` builds for a v1 group chat.
 */
class IosEmittedGroupVectorTest {
    private val file: JsonObject by lazy { VectorFiles.read("ios-emitted-v1.json") }
    private val keyed: JsonObject by lazy { VectorFiles.read("keyed-v1.json") }
    private val carol by lazy { VectorParty.identity(file, "carol") }
    private val parties by lazy {
        mapOf("carol" to carol, "alice" to VectorParty.identity(keyed, "alice"), "bob" to VectorParty.identity(keyed, "bob"))
    }

    private fun frame(name: String): JsonObject =
        file
            .getValue("frames")
            .jsonObject
            .getValue(name)
            .jsonObject

    private fun field(
        entry: JsonObject,
        key: String,
    ): String = entry.getValue(key).jsonPrimitive.content

    /** The node ids of the parties the vector entry lists as the group's members. */
    private fun memberIds(entry: JsonObject): List<String> =
        entry.getValue("members").jsonArray.map { parties.getValue(it.jsonPrimitive.content).nodeId }

    private fun relayOf(name: String): RelayEnvelope {
        val bytes = field(frame(name), "wire").fromHex()
        val wire = checkNotNull(WireCodec.decodeWire(bytes)) { "$name does not decode" }
        assertArrayEquals("$name's WireEnvelope re-encodes differently", bytes, WireCodec.encodeWire(wire))
        assertTrue("$name's signature does not verify", MessageCrypto.verify(carol.bundle, wire.sig, wire.signed))
        val relay = checkNotNull(WireCodec.decodeEnvelope(wire.signed)) { "$name's RelayEnvelope does not decode" }
        assertArrayEquals("$name's RelayEnvelope re-encodes differently", wire.signed, WireCodec.encodeEnvelope(relay))
        assertEquals(carol.nodeId, relay.senderId)
        return relay
    }

    /** The roster rules `InboundPipeline.vetRoster` applies on first sight, for a frame [relay] carries. */
    private fun assertRosterVerifies(
        name: String,
        relay: RelayEnvelope,
    ): GroupInfo {
        val entry = frame(name)
        val group = checkNotNull(relay.group) { "$name carries no group" }
        val expectedId = field(entry, "groupId")
        assertEquals("the derived id", expectedId, Conversations.groupIdFor(memberIds(entry)))
        assertEquals("the frame's group id", expectedId, group.id)
        val founding = (group.members + group.departed.orEmpty()).distinct()
        assertTrue(founding.size <= GroupInfo.MAX_MEMBERS)
        assertEquals("the founding roster derives the id", group.id, Conversations.groupIdFor(founding))
        assertEquals(memberIds(entry).toSet(), founding.toSet())
        assertTrue("the sender is a founding member", relay.senderId in founding)
        assertEquals("createdBy", carol.nodeId, group.createdBy)
        return group
    }

    @Test
    fun `carol's group chat derives its id from the roster and opens for alice and bob`() {
        val relay = relayOf("carolGroupChat")
        assertEquals(FrameType.CHAT, relay.type)
        assertNull("a group chat names no recipient", relay.recipientId)
        val group = assertRosterVerifies("carolGroupChat", relay)
        val chat = checkNotNull(WireCodec.decodePayload<ChatContent>(relay.payload))
        assertArrayEquals(relay.payload, WireCodec.encodePayload(chat))
        assertEquals("a group chat carries no cleartext body", "", chat.body)
        val enc = checkNotNull(chat.enc)
        assertNull("a v1 group chat has no sender-key header", enc.g)
        val header = MessageCrypto.header(relay.id, relay.senderId, relay.sentAt, group.id)
        for (who in listOf("alice", "bob")) {
            val party = parties.getValue(who)
            val opened = party.crypto.open(enc, header, party.nodeId)
            assertNotNull("$who cannot open carol's group chat", opened)
            assertEquals(field(frame("carolGroupChat"), "body"), opened?.body)
            assertTrue("$who's content version is supported", checkNotNull(opened).isSupported())
        }
        assertEquals(
            "one wrapped key per other member, sorted",
            listOf(parties.getValue("alice").nodeId, parties.getValue("bob").nodeId).sorted(),
            enc.keys.map { it.to },
        )
    }

    @Test
    fun `carol's rename names the group on the roster it derives from`() {
        val relay = relayOf("carolGroupRename")
        assertEquals(FrameType.GROUP_UPDATE, relay.type)
        val group = assertRosterVerifies("carolGroupRename", relay)
        assertEquals(field(frame("carolGroupRename"), "name"), group.name)
    }

    @Test
    fun `carol's leave names the group she leaves`() {
        val relay = relayOf("carolGroupLeave")
        assertEquals(FrameType.GROUP_LEAVE, relay.type)
        val leave = checkNotNull(WireCodec.decodePayload<GroupLeaveContent>(relay.payload))
        assertArrayEquals(relay.payload, WireCodec.encodePayload(leave))
        assertEquals(field(frame("carolGroupLeave"), "groupId"), leave.groupId)
        assertEquals(Conversations.groupIdFor(memberIds(frame("carolGroupLeave"))), leave.groupId)
    }

    @Test
    fun `carol's profile carries the four capabilities her port implements and no others`() {
        val relay = relayOf("carolProfile")
        val profile = checkNotNull(WireCodec.decodePayload<ProfileContent>(relay.payload))
        val caps = checkNotNull(profile.capabilities)
        assertEquals(
            Protocol.CAP_E2E or Protocol.CAP_GROUPS or Protocol.CAP_REACTIONS or Protocol.CAP_STORE_FORWARD,
            caps,
        )
        assertEquals("no ratchet: Android must keep sealing v1 to her", 0L, caps and Protocol.CAP_RATCHET)
    }
}
