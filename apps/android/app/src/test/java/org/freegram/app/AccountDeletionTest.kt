package org.freegram.app

import org.freegram.app.protocol.AccountDeletion
import org.freegram.app.protocol.Nip01Protocol
import org.freegram.app.protocol.ProfileEvent
import org.junit.Assert.*
import org.junit.Test

class AccountDeletionTest {
    private val secret = ByteArray(32).also { it[31] = 3 }

    @Test fun requestsCoverEveryIdAndStaySmall() {
        val ids = (0 until 95).map { i -> "%064x".format(i) }
        val requests = AccountDeletion.requests(secret, ids + ids.take(5), 1_700_000_000)
        assertEquals("Deleted account", ProfileEvent.nameOf(requests.first()))
        val deletions = requests.filter { it.kind == AccountDeletion.DELETE_KIND }
        assertEquals(3, deletions.size)
        assertEquals(ids.toSet(), deletions.flatMap { d -> d.tags.map { it[1] } }.toSet())
        assertEquals(AccountDeletion.VANISH_KIND, requests.last().kind)
        requests.drop(1).forEach { assertTrue(AccountDeletion.isRequest(it)) }
        assertTrue(requests.all { Nip01Protocol.toJson(it).length <= AccountDeletion.MAX_BYTES })
        // A forged request is refused.
        assertFalse(AccountDeletion.isRequest(requests.last().copy(content = "changed")))
    }
}
