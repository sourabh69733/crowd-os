package org.freegram.app.identity

import android.app.ActivityManager
import android.content.Context
import java.security.KeyStore

/**
 * Erases everything Freegram keeps on this phone, then Android closes the app.
 *
 * The Keystore key goes first: it seals the stored ID, so once it is gone the sealed ID can't be opened even
 * if deleted files are later recovered from storage. Then Android clears all app data (database, photos,
 * settings, scheduled work) as "Clear storage" in system settings does, and ends the process.
 */
object PanicWipe {
    fun wipe(context: Context) {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KeystoreSecretWrapper.DEFAULT_ALIAS)
        }
        val cleared = context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
        if (!cleared) fallback(context)
    }

    /** Used only if Android refuses to clear app data: delete our files one by one, then exit. */
    private fun fallback(context: Context) {
        context.filesDir.parentFile?.listFiles()?.filter { it.name != "lib" }?.forEach { it.deleteRecursively() }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
