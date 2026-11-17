package com.playbridge.sender.together

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPartyStoreTest {
    private var now = 1_800_000_000_000L
    private val prefs = MemoryPreferences()

    /** Reversible but not plain, so a test can tell sealed text from the party itself. */
    private val cipher = object : SavedPartyCipher {
        override fun seal(plain: String) = "sealed:" + plain.reversed()
        override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed()
    }

    private fun store() = SavedPartyStore(prefs, cipher) { now }

    private fun party(room: String, host: Boolean = false, joinedAt: Long = now, endsAt: Long? = now + 3_600_000L) =
        SavedParty(
            roomId = room,
            relay = "party.playbridge.app",
            host = host,
            saved = "secret-$room",
            joinedAtMs = joinedAt,
            endsAtMs = SavedParties.endsAt(endsAt, joinedAt),
        )

    @Test
    fun `keeps parties sealed and reads them back after a restart`() {
        store().put(party("a", host = true).copy(hostName = "Atul", own = listOf(SavedOwnDevice("tv", "box"))))
        val raw = prefs.getString("a", null)!!
        assertFalse("stored in the clear", raw.contains("secret-a"))

        val back = store().get("a")!!
        assertEquals("secret-a", back.saved)
        assertTrue(back.host)
        assertEquals("Atul", back.hostName)
        assertEquals(listOf(SavedOwnDevice("tv", "box")), back.own)
        assertFalse("logged the saved keys", back.toString().contains("secret"))
    }

    @Test
    fun `drops parties that ended, and undated ones after the relay's party length`() {
        val store = store()
        store.put(party("dated", endsAt = now + 60_000L))
        store.put(party("undated", endsAt = null))
        assertEquals(setOf("dated", "undated"), store.parties.value.map { it.roomId }.toSet())

        now += 2 * 60_000L
        store.prune()
        assertEquals(listOf("undated"), store.parties.value.map { it.roomId })
        assertNull(prefs.getString("dated", null))

        now += SavedParties.UNDATED_LIFETIME_MS
        assertNull(store.get("undated"))
        store.prune()
        assertTrue(store.parties.value.isEmpty())
    }

    @Test
    fun `lists newest first, keeps the newest twenty, and forgets on request`() {
        val store = store()
        repeat(SavedParties.MAX_SAVED + 2) { index -> store.put(party("r$index", joinedAt = now + index)) }
        val rooms = store.parties.value.map { it.roomId }
        assertEquals(SavedParties.MAX_SAVED, rooms.size)
        assertEquals("r21", rooms.first())
        assertFalse("r0" in rooms || "r1" in rooms)
        assertNull(prefs.getString("r0", null))

        store.remove("r21")
        assertNull(store.get("r21"))
        assertNull(prefs.getString("r21", null))
    }

    @Test
    fun `updates an entry and ignores damaged ones`() {
        val store = store()
        store.put(party("a"))
        store.update("a") { it.copy(hostName = "Sam") }
        assertEquals("Sam", store.get("a")?.hostName)
        assertEquals("Sam's party", store.get("a")?.title())

        prefs.edit().putString("broken", "sealed:not json").apply()
        val reopened = store()
        assertEquals(listOf("a"), reopened.parties.value.map { it.roomId })
        assertNull(prefs.getString("broken", null))
    }

    @Test
    fun `says when a party ends`() {
        val p = party("a")
        assertEquals("Ends in 2 h 10 min", SavedParties.endsIn(p.copy(endsAtMs = now + 130 * 60_000L), now))
        assertEquals("Ends in 3 h", SavedParties.endsIn(p.copy(endsAtMs = now + 180 * 60_000L), now))
        assertEquals("Ends in 5 min", SavedParties.endsIn(p.copy(endsAtMs = now + 5 * 60_000L + 1), now))
        assertEquals("Ends in less than a minute", SavedParties.endsIn(p.copy(endsAtMs = now + 1_000L), now))
        assertEquals("Your party", p.copy(host = true).title())
        assertEquals("Watch party", p.title())
    }
}

/** Just enough SharedPreferences for the store; edits apply at once. */
private class MemoryPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int) = defValue
    override fun getLong(key: String, defValue: Long) = defValue
    override fun getFloat(key: String, defValue: Float) = defValue
    override fun getBoolean(key: String, defValue: Boolean) = defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { values[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun remove(key: String) = apply { values.remove(key) }
        override fun clear() = apply { values.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}
