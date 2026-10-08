package org.freegram.app

/** Settings fixed at build time. */
object FreegramConfig {
    /**
     * The maintainer every new install follows, so reporting and hiding work from day one (an app-store requirement
     * for apps with user posts). Users can switch it off or remove it in Settings → Maintainers. Empty: none.
     */
    const val DEFAULT_MAINTAINER_NPUB = "npub19mzk6h08t2g63hvax6246h0pu2t02msqtwct0tcfqzl2hn3vmjwq7sjtq3"
}
