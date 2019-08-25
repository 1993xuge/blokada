package g11n

import core.COMMON
import core.Result
import core.Url
import core.pages
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

object Events {

}

class Main(
        urls: () -> Map<Url, Prefix>,
        doPutTranslation: (Key, Translation) -> Result<Boolean>
) {

    private val fetcher = TranslationsFetcher(urls, doPutTranslation = doPutTranslation)

    fun load() = GlobalScope.async(COMMON) {
        fetcher.load()
    }

    fun sync() = GlobalScope.async(COMMON) {
        fetcher.sync()
        fetcher.save()
    }

    fun invalidateCache() = GlobalScope.async(COMMON) {
        fetcher.invalidateCache()
    }
}

val g11Manager by lazy {
    g11n.Main(
            urls = { mapOf(
                    pages.filtersStringsFallback().toExternalForm() to "filters",
                    pages.filtersStrings().toExternalForm() to "filters"
            ) },
            doPutTranslation = { key, value ->
                core.Result.of { i18n.set(key, value); true }
            }
    )
}
