package org.freegram.app

/** Settings fixed at build time. */
object FreegramConfig {
    /**
     * The maintainer every new install follows, so reporting and hiding work from day one (an app-store requirement
     * for apps with user posts). Users can switch it off or remove it in Settings → Maintainers. Empty: none.
     */
    const val DEFAULT_MAINTAINER_NPUB = ""
}
