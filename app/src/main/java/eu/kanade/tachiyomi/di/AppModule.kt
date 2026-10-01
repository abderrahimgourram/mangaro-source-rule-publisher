package eu.kanade.tachiyomi.di

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.source.internal.azora.Azora
import eu.kanade.tachiyomi.source.internal.hijala.Hijala
import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.internal.mangalek.MangaLek
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.internal.mangaswat.MangaSwat
import mihon.domain.source.registry.DefaultInternalSourceRegistry
import mihon.domain.source.registry.DefaultSourceCollisionPolicy
import mihon.domain.source.registry.InternalSourceRegistry
import mihon.domain.source.registry.SourceCollisionPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

private val lock = Any()

class AppModule(val app: Application) : InjektModule {

    private var sqlDriverRef: WeakReference<SqlDriver>? = null

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)
        addSingleton<Context>(app)

        addSingletonFactory<SqlDriver> {
            synchronized(lock) {
                sqlDriverRef?.get()?.let { return@synchronized it }

                AndroidxSqliteDriver(
                    driver = BundledSQLiteDriver(),
                    databaseType = AndroidxSqliteDatabaseType.FileProvider(app, "tachiyomi.db"),
                    schema = Database.Schema,
                    configuration = AndroidxSqliteConfiguration(
                        isForeignKeyConstraintsEnabled = true,
                    ),
                )
                    .also { sqlDriverRef = WeakReference(it) }
            }
        }
        addSingletonFactory {
            Database(
                driver = get(),
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(
                    memoAdapter = MemoColumnAdapter,
                ),
            )
        }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory<XML> {
            XML.v1 {
                policy {
                    ignoreUnknownChildren()
                    autoPolymorphic = true
                }
                xmlDeclMode = XmlDeclMode.Charset
                xmlVersion = XmlVersion.XML10
                setIndent(2)
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }

        addSingletonFactory { NetworkHelper(app, get()) }
        addSingletonFactory { JavaScriptEngine(app) }

        addSingletonFactory<eu.kanade.tachiyomi.source.repair.RuleRepairEngine> {
            val verifier = eu.kanade.tachiyomi.source.repair.RuleVerifier(eu.kanade.tachiyomi.BuildConfig.SOURCE_RULES_PUBLIC_KEY)
            val store = eu.kanade.tachiyomi.source.repair.RuleStore(java.io.File(app.filesDir, "source-rules"), verifier)
            val url = eu.kanade.tachiyomi.BuildConfig.SOURCE_RULES_URL
            val transport = if (url.isBlank() || eu.kanade.tachiyomi.BuildConfig.SOURCE_RULES_PUBLIC_KEY.isBlank()) {
                eu.kanade.tachiyomi.source.repair.RuleTransport { null }
            } else eu.kanade.tachiyomi.source.repair.HttpsRuleTransport(url, get<NetworkHelper>().client)
            eu.kanade.tachiyomi.source.repair.RuleRepairEngine(store, transport, existingChapters = { sourceId, manga ->
                val local = get<tachiyomi.domain.manga.repository.MangaRepository>().getMangaByUrlAndSourceId(manga.url, sourceId)
                if (local == null) emptyList() else get<tachiyomi.domain.chapter.repository.ChapterRepository>().getChapterByMangaId(local.id).map { chapter ->
                    eu.kanade.tachiyomi.source.model.SChapter.create().apply { this.url = chapter.url; name = chapter.name; memo = chapter.memo }
                }
            })
        }
        addSingletonFactory<InternalSourceRegistry> {
            DefaultInternalSourceRegistry(
                listOf(
                    TeamX(),
                    MangaTime(),
                    Azora(),
                    Hijala(),
                    MangaLek(),
                    MangaDar(),
                    MangaSwat(),
                ).map { source ->
                    val engine = get<eu.kanade.tachiyomi.source.repair.RuleRepairEngine>()
                    eu.kanade.tachiyomi.source.repair.RepairableSource(source, engine)
                },
            )
        }
        addSingletonFactory<SourceCollisionPolicy> { DefaultSourceCollisionPolicy() }
        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get(), get(), get()) }
        addSingletonFactory { ExtensionManager(app) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }

        addSingletonFactory { ImageSaver(app) }

        addSingletonFactory { AndroidStorageFolderProvider(app) }
        addSingletonFactory { LocalSourceFileSystem(get()) }
        addSingletonFactory { LocalCoverManager(app, get()) }
        addSingletonFactory { StorageManager(app, get()) }

        // Asynchronously init expensive components for a faster cold start
        ContextCompat.getMainExecutor(app).execute {
            get<NetworkHelper>()

            get<SourceManager>()

            get<Database>()

            get<DownloadManager>()
        }
    }
}
