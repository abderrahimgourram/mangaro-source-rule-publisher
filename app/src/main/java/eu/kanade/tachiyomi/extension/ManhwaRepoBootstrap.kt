package eu.kanade.tachiyomi.extension

import android.content.Context
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Registers the independent, third-party extension store once. Extensions themselves
 * are NEVER auto-installed. Failed offline initialization is retried on next launch;
 * once initialized, removing the store manually is respected.
 */
object ManhwaRepoBootstrap {
    private const val BOOTSTRAP_PREFERENCES = "manhwa_ar_bootstrap"
    private const val DONE_KEY = "keiyoushi_store_seeded_v1"
    private const val STORE_URL = "https://github.com/keiyoushi/extensions/raw/repo/repo.json"
    private const val CANONICAL_INDEX_URL = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"

    suspend fun install(context: Context) {
        val prefs = context.getSharedPreferences(BOOTSTRAP_PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE_KEY, false)) return

        try {
            val repository = Injekt.get<ExtensionStoreRepository>()
            val alreadyAdded = repository.getAll().any {
                it.indexUrl == STORE_URL || it.indexUrl == CANONICAL_INDEX_URL
            }
            if (!alreadyAdded) {
                // The upstream repository fetches repo.json, follows index_v2,
                // saves its signing key and updates the extension store database.
                repository.insert(STORE_URL).getOrThrow()
            }
            prefs.edit().putBoolean(DONE_KEY, true).apply()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Network unavailable: preserve retry on the next launch.
            logcat(LogPriority.WARN, error) { "Manhwa AR: default extension store setup failed" }
        }
    }
}
