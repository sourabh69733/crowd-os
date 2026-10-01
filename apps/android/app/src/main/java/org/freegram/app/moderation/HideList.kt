package org.freegram.app.moderation

import org.freegram.app.protocol.BulletinEvent

/**
 * A maintainer's hide list: a NIP-51 mute list (kind 10000) with public `e` (post) and `p` (author) tags,
 * signed by that maintainer's own key. The newest list per maintainer replaces older ones.
 */
data class HideList(val maintainer: String, val createdAt: Long, val posts: Set<String>, val authors: Set<String>) {
    fun toTags(): Array<Array<String>> =
        (posts.sorted().map { arrayOf("e", it) } + authors.sorted().map { arrayOf("p", it) }).toTypedArray()

    companion object {
        const val KIND = 10000
        const val MAX_ENTRIES = 2000
        const val MAX_BYTES = 256 * 1024

        /** Parses a verified event. Unknown tags are ignored; malformed or oversized lists are rejected. */
        fun of(event: BulletinEvent): HideList? {
            if (event.kind != KIND || event.tags.size > MAX_ENTRIES) return null
            val posts = mutableSetOf<String>(); val authors = mutableSetOf<String>()
            for (tag in event.tags) {
                val value = tag.getOrNull(1)?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: continue
                when (tag[0]) { "e" -> posts += value; "p" -> authors += value }
            }
            return HideList(event.pubkey, event.createdAt, posts, authors)
        }
    }
}
