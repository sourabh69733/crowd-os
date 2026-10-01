# Freegram moderation

Status: hide lists, private reports and appeals built 27 September 2026.

## Decisions (27 September 2026)

- A maintainers team, starting with one person and growing to 3–4.
- Each maintainer signs with their own key, so users can tell them apart and choose whom to follow.
- Users can appeal a decision.

## Hide lists (built)

- Each maintainer publishes a **NIP-51 mute list** (kind 10000) signed by their own key, with public `e` tags (posts) and `p` tags (authors). The newest list per maintainer replaces older ones; an older list cannot undo a newer one.
- A phone follows up to 5 maintainers and can switch each off. Lists are fetched with **Refresh hide lists** from both relays and are accepted only if the signature is valid and the signer is a followed maintainer.
- A post covered by an enabled list is not shown, not offered to nearby phones, and not accepted from them or from relays. The phone's own posts can still be saved and sent. Hidden posts are not deleted; switching the maintainer off shows them again.
- A maintainer uses the same app: **Hide this post / author (maintainer)** on a selected post signs a new list version, applies it locally and sends it to both relays. Their own key is added as a followed maintainer automatically. Entries can be unhidden from the Maintainers section.

Limits: hide lists are public, so anyone can see what a maintainer hid. Lists are fetched only on manual refresh and are not yet carried between phones nearby. Publishing a list is not queued for retry; the maintainer edits again when online. There is no default maintainer yet; users add one by `npub`.

## Private reports and appeals (built)

- **Report**: the reporter sends a NIP-17 private message (NIP-44 encryption inside a NIP-59 gift wrap) to each followed maintainer, naming the post ID and a reason. Relays and other users see only an encrypted message from a one-time key.
- **Maintainer inbox**: the maintainer's app fetches and decrypts gift-wrapped messages addressed to their key and lists reports next to the post.
- **Appeal**: an author whose post or key is hidden sends the same kind of private message to the maintainer who hid it. The maintainer's decision is visible as the next version of their public list.

Implementation: encryption is Quartz 1.16.0's NIP-44 v2 (decision A); our code only assembles and checks the NIP-59 layers. Tests pass NIP-44's official conversation-key and encryption vectors, round-trip reports and appeals, reject tampered or forged wraps, other recipients, and a seal whose signer differs from the rumor's claimed sender. Outer timestamps are randomised up to two days back.

Limits: the maintainer learns the reporter's key (reports are not anonymous). Messages go to the two configured relays, so a maintainer must read from at least one of them; NIP-17 inbox relay lists (kind 10050) are not used yet. The inbox is fetched on request and not stored. Relays see that someone sent the maintainer a message, and when it was fetched.
