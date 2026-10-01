package org.freegram.app.protocol

private fun isId(value: String?) = value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

/**
 * Replies are ordinary kind-1 posts that point at the post they answer (NIP-10 marked `e` tags):
 * `["e", <root>, "", "root"]`, plus `["e", <parent>, "", "reply"]` when answering a reply, and `p` tags for the authors.
 * Freegram shows each thread flat under its root post.
 */
object Replies {
    /** The top-level post this post replies to, or null if it is a top-level post. Mentions don't count. */
    fun rootOf(event: BulletinEvent): String? {
        val refs = event.tags.filter { it.getOrNull(0) == "e" && isId(it.getOrNull(1)) && it.getOrNull(3) != "mention" }
        if (refs.isEmpty()) return null
        return (refs.firstOrNull { it.getOrNull(3) == "root" } ?: refs.first())[1]
    }

    /** Tags for a reply to [parent]; [rootAuthor] is the root post's author when known. */
    fun tags(parent: BulletinEvent, rootAuthor: String?): Array<Array<String>> {
        val root = rootOf(parent)
        return buildList {
            if (root == null) {
                add(arrayOf("e", parent.id, "", "root"))
            } else {
                add(arrayOf("e", root, "", "root"))
                add(arrayOf("e", parent.id, "", "reply"))
            }
            add(arrayOf("p", parent.pubkey))
            if (rootAuthor != null && rootAuthor != parent.pubkey) add(arrayOf("p", rootAuthor))
        }.toTypedArray()
    }
}

/** Likes are NIP-25 reactions (kind 7). Removing one is a NIP-09 deletion (kind 5) of the like. */
object Likes {
    const val KIND = 7
    const val DELETE_KIND = 5
    const val MAX_BYTES = 4096

    fun sign(secret: ByteArray, post: BulletinEvent, createdAt: Long): BulletinEvent =
        Nip01Protocol.signEvent(secret, KIND, "+", createdAt, arrayOf(arrayOf("e", post.id), arrayOf("p", post.pubkey), arrayOf("k", "1")))

    fun signUnlike(secret: ByteArray, like: BulletinEvent, createdAt: Long): BulletinEvent =
        Nip01Protocol.signEvent(secret, DELETE_KIND, "", createdAt, arrayOf(arrayOf("e", like.id), arrayOf("k", KIND.toString())))

    /** The post a verified reaction likes, or null. NIP-25: the last `e` tag is the target; "-" is a dislike, not a like. */
    fun likedPost(event: BulletinEvent): String? {
        if (event.kind != KIND || event.content == "-" || event.content.length > 64) return null
        if (!Nip01Protocol.verifySigned(event, MAX_BYTES)) return null
        return event.tags.lastOrNull { it.getOrNull(0) == "e" }?.getOrNull(1)?.takeIf(::isId)
    }

    /** Whether [event] is this phone's verified request to remove its own like. */
    fun isUnlike(event: BulletinEvent) = event.kind == DELETE_KIND && Nip01Protocol.verifySigned(event, MAX_BYTES)
}
