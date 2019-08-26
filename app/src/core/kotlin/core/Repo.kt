package core

import kotlinx.coroutines.runBlocking
import java.net.URL
import java.util.*

data class RepoContent(
        val contentPath: URL?,
        val locales: List<Locale>,
        val newestVersionCode: Int,
        val newestVersionName: String,
        val downloadLinks: List<URL>,
        internal val fetchedUrl: String
)

val repo by lazy {
    runBlocking {
        RepoImpl()
    }
}

class RepoImpl {

    val url = newPersistedProperty2("repo_url", zeroValue = { "" })

    init {
        url.doWhenSet().then {
            v("url set", url())
            content.refresh(force = true)
        }
    }

    val lastRefreshMillis = newPersistedProperty2("repo_refresh", zeroValue = { 0L })

    private val repoRefresh = {
        v("repo refresh start")
        val repoURL = java.net.URL(url())
        val fetchTimeout = 10 * 10000

        try {
            val repo2 = loadGzip(openUrl(repoURL, fetchTimeout))
            val locales = repo2[1].split(" ").map {
                // Because Java APIs suck
                val parts = it.split("_")
                when(parts.size) {
                    3 -> Locale(parts[0], parts[1], parts[2])
                    2 -> Locale(parts[0], parts[1])
                    else -> Locale(parts[0])
                }
            }
            v("repo downloaded")

            lastRefreshMillis %= time.now()
            RepoContent(
                    contentPath = URL(repo2[0]),
                    locales = locales,
                    newestVersionCode = repo2[2].toInt(),
                    newestVersionName = repo2[3],
                    downloadLinks = repo2.subList(4, repo2.size).map { URL(it) },
                    fetchedUrl = url()
            )
        } catch (ex: Exception) {
            e("repo refresh fail", ex)
            if (ex is java.io.FileNotFoundException) {
                w("app version is obsolete", ex)
                version.obsolete %= true
            }
            throw ex
        }
    }

    val content = newPersistedProperty(ARepoPersistence(),
            zeroValue = { RepoContent(null, listOf(), 0, "", listOf(), "") },
            refresh = { repoRefresh() },
            shouldRefresh = {
                val ttl = 86400 * 1000

                when {
                    it.fetchedUrl != url() -> true
                    lastRefreshMillis() + ttl < time.now() -> true
                    it.downloadLinks.isEmpty() -> true
                    it.contentPath == null -> true
                    it.locales.isEmpty() -> true
                    else -> false
                }
            }
    )
}

class ARepoPersistence() : PersistenceWithSerialiser<RepoContent>() {

    val p by lazy { serialiser("repo") }

    override fun read(current: RepoContent): RepoContent {
        return try {
            RepoContent(
                    contentPath = URL(p.getString("contentPath", "")),
                    locales = p.getStringSet("locales", setOf()).map { Locale(it) }.toList(),
                    newestVersionCode = p.getInt("code", 0),
                    newestVersionName = p.getString("name", ""),
                    downloadLinks = p.getStringSet("links", setOf()).map { URL(it) }.toList(),
                    fetchedUrl = p.getString("fetchedUrl", "")
            )
        } catch (e: Exception) {
            current
        }
    }

    override fun write(source: RepoContent) {
        val e = p.edit()
        e.putString("contentPath", source.contentPath.toString())
        e.putStringSet("locales", source.locales.map { it.toString() }.toSet())
        e.putInt("code", source.newestVersionCode)
        e.putString("name", source.newestVersionName)
        e.putStringSet("links", source.downloadLinks.map { it.toString() }.toSet())
        e.putString("fetchedUrl", source.fetchedUrl)
        e.apply()
    }

}

