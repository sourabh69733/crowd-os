package org.freegram.app.moderation

import org.freegram.app.protocol.BulletinEvent

/**
 * People a maintainer suggests in Discover: a NIP-51 follow set (kind 30000, `d` = [D_TAG]) with public `p` tags,
 * signed by that maintainer's own key. The newest list per maintainer replaces older ones.
 */
data class SuggestList(val maintainer: String, val createdAt: Long, val people: Set<String>) {
    fun toTags(): Array<Array<String>> = (listOf(arrayOf("d", D_TAG)) + people.sorted().map { arrayOf("p", it) }).toTypedArray()

    companion object {
        const val KIND = 30000
        const val D_TAG = "freegram-suggested"
        const val MAX_PEOPLE = 200
        const val MAX_BYTES = 32 * 1024

        /** Parses a verified event; anything that isn't Freegram's suggestion list is rejected. */
        fun of(event: BulletinEvent): SuggestList? {
            if (event.kind != KIND || event.tags.none { it.getOrNull(0) == "d" && it.getOrNull(1) == D_TAG }) return null
            val people = event.tags.filter { it.getOrNull(0) == "p" }.mapNotNull { tag ->
                tag.getOrNull(1)?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            }.toSet()
            if (people.size > MAX_PEOPLE) return null
            return SuggestList(event.pubkey, event.createdAt, people)
        }
    }
}

/** Public posts opt in to Discover with this hashtag (`["t", "freegram"]`), which relays can filter on. */
object Discover {
    const val TAG = "freegram"
    fun tag(): Array<String> = arrayOf("t", TAG)
    fun isTagged(event: BulletinEvent) = event.tags.any { it.getOrNull(0) == "t" && it.getOrNull(1) == TAG }
}
