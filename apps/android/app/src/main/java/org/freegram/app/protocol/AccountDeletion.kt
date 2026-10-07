package org.freegram.app.protocol

/**
 * What "Delete my account" asks servers to do before the phone is erased:
 * a profile renamed "Deleted account", NIP-09 deletion requests (kind 5) for this ID's known posts and likes,
 * and a NIP-62 request to vanish (kind 62), which servers that support it apply to everything from this ID.
 * Servers decide whether to honour these; copies on phones can't be recalled.
 */
object AccountDeletion {
    const val DELETE_KIND = 5
    const val VANISH_KIND = 62
    const val MAX_BYTES = 4096
    private const val IDS_PER_REQUEST = 40

    fun requests(secret: ByteArray, ownEventIds: List<String>, createdAt: Long): List<BulletinEvent> {
        val profile = ProfileEvent.sign(secret, "Deleted account", createdAt)
        val deletions = ownEventIds.distinct().chunked(IDS_PER_REQUEST).map { ids ->
            Nip01Protocol.signEvent(secret, DELETE_KIND, "Account deleted", createdAt, ids.map { arrayOf("e", it) }.toTypedArray())
        }
        val vanish = Nip01Protocol.signEvent(secret, VANISH_KIND, "Account deleted", createdAt, arrayOf(arrayOf("relay", "ALL_RELAYS")))
        return listOf(profile) + deletions + vanish
    }

    fun isRequest(event: BulletinEvent) =
        (event.kind == DELETE_KIND || event.kind == VANISH_KIND) && Nip01Protocol.verifySigned(event, MAX_BYTES)
}
