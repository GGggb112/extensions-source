package eu.kanade.tachiyomi.extension.all.yellownote

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.extension.all.yellownote.Preferences.baseUrl
import eu.kanade.tachiyomi.extension.all.yellownote.Preferences.language
import eu.kanade.tachiyomi.extension.all.yellownote.Preferences.preferenceMigration
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.i18n.Intl
import keiyoushi.utils.firstInstance
import keiyoushi.utils.getPreferences
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class YellowNote :
    HttpSource(),
    ConfigurableSource {
    override val id get() = 170542391855030753
    override val lang = "all"
    override val baseUrl by lazy { preferences.baseUrl() }
    override val name = "小黄书"
    override val supportsLatest = true
    override val client = network.cloudflareClient
    private val preferences = getPreferences { preferenceMigration() }
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val intl = Intl(
        language = preferences.language(),
        baseLanguage = LanguageUtils.baseLocale.language,
        availableLanguages = LanguageUtils.supportedLocaleTags.toSet(),
        classLoader = this::class.java.classLoader!!,
    )
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd", Locale.US)
    private val dateRegex = """\d{4}\.\d{2}\.\d{2}""".toRegex()
    private val styleUrlRegex = """background-image\s*:\s*url\('([^']+)'\)""".toRegex()
    private val mediaCountRegex = """\d+P( \+ \d+V)?""".toRegex()
    private val hasVideoRegex = """\d+P\s*\+\s*\d+V""".toRegex()
    private val mangaSelector = "div.list.photo-list > div.item.photo, div.list.amateur-list > div.item.amateur"
    private val nextPageSelector = "div.pager:first-of-type > a.pager-next"
    private val imageSelector = "div.list.photo-items > div.item.photo-image, div.list.amateur-items > div.item.amateur-image"
    private val videosRegex = """var videos = (\[.*?\]);""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val videoUrlRegex = """"url":"([^"]+)"""".toRegex()
    private val domainRegex = """var domain = "([^"]+)";""".toRegex()

    // Track content type filter state for popular/latest (which don't receive filters)
    private var contentTypeState = "all"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Preferences.buildPreferences(screen.context, intl)
            .forEach(screen::addPreference)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()
        val allMangas = document.select(mangaSelector).map { element ->
            SManga.create().apply {
                val mangaEl = element.selectFirst("a")!!
                setUrlWithoutDomain(mangaEl.absUrl("href"))
                val formatMediaCount = element.select("div.tags > div")
                    .map { it.text() }
                    .firstOrNull { mediaCountRegex.matches(it) }
                    ?.let { "($it)" }
                    .orEmpty()
                title = "${mangaEl.attr("title")}$formatMediaCount"
                thumbnail_url = parseUrlFormStyle(mangaEl.selectFirst("div.img"))
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
        // Filter by content type
        val mangas = when (contentTypeState) {
            "images" -> allMangas.filter { manga ->
                // Keep only posts WITHOUT videos (no "+V" in title)
                !hasVideoRegex.containsMatchIn(manga.title)
            }
            "videos" -> allMangas.filter { manga ->
                // Keep only posts WITH videos ("+V" in title)
                hasVideoRegex.containsMatchIn(manga.title)
            }
            else -> allMangas // "all" - show everything
        }
        val hasNextPage = document.selectFirst(nextPageSelector) != null
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseUrlFormStyle(element: Element?): String? = element
        ?.attr("style")
        ?.let { styleUrlRegex.find(it) }
        ?.groupValues
        ?.get(1)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/photos/sort-hot/$page.html", headers)
    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/photos/$page.html", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val categorySelector = filters.firstInstance<CategorySelector>()
        val sortSelector = filters.firstInstance<SortSelector>()
        val contentTypeSelector = filters.firstInstance<ContentTypeSelector>()

        // Update content type state for all browse modes
        if (contentTypeSelector != null) {
            contentTypeState = contentTypeSelector.selectedKey()
        }

        val uriPart = when {
            query.isBlank() -> categorySelector.toUriPart()
            else -> "photos/keyword-$query"
        }

        val httpUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(uriPart)
            .addPathSegment(sortSelector.toUriPart())
            .addPathSegments("$page.html")
            .build()
        return GET(httpUrl, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val infoCardElement = document.selectFirst("div.info-card.photo-detail")!!
            val name = parseInfoByIcon(infoCardElement, "i.fa-address-card")!!
            val mediaCount = parseInfoByIcon(infoCardElement, "i.fa-image")!!
            val no = parseInfoByIcon(infoCardElement, "i.fa-file")?.let { " $it" }.orEmpty()
            val categories =
                parseInfosByIcon(infoCardElement, "i.fa-video-camera")?.filter { it != "-" }
            val filters = parseInfosByIcon(infoCardElement, "i.fa-filter")
            val tags = parseInfosByIcon(infoCardElement, "i.fa-tags")
            title = "$name$no($mediaCount)"
            author = infoCardElement.selectFirst("div.item.floating")
                ?.text()
                ?: parseInfoByIcon(infoCardElement, "i.fa-circle-user")
            genre = listOfNotNull(categories, filters, tags)
                .flatten()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    private fun parseInfosByIcon(infoCardElement: Element, iconClass: String): List<String>? = infoCardElement
        .selectFirst("div.item:has(.icon > $iconClass)")
        ?.selectFirst("div.text")
        ?.children()
        ?.map { it.text() }

    private fun parseInfoByIcon(infoCardElement: Element, iconClass: String): String? = infoCardElement
        .selectFirst("div.item:has(.icon > $iconClass)")
        ?.selectFirst("div.text")
        ?.text()

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val doc = Jsoup.parse(html)
        val infoCardElement = doc.selectFirst("div.info-card.photo-detail")!!
        val uploadAt = parseInfoByIcon(infoCardElement, "i.fa-calendar-days")
            ?.let { dateFormat.tryParse(it) }
            ?: parseUploadDateFromVersionInfo(doc)
            ?: 0L
        val maxPage = doc.select("div.pager:first-of-type a.pager-num").last()?.text()?.toInt() ?: 1
        val basePageUrl = response.request.url.toString()
            .removeSuffix(".html")

        val chapters = mutableListOf<SChapter>()

        // Check if this post has videos
        val hasVideos = videosRegex.find(html) != null
        if (hasVideos) {
            // Add video chapter first
            chapters.add(
                SChapter.create().apply {
                    setUrlWithoutDomain("$basePageUrl/1.html#video")
                    name = "\uD83C\uDFAC Videos"
                    date_upload = uploadAt
                },
            )
        }

        // Add image chapters (paginated)
        for (page in maxPage downTo 1) {
            chapters.add(
                SChapter.create().apply {
                    setUrlWithoutDomain("$basePageUrl/$page.html")
                    name = "\uD83D\uDCF7 Page $page"
                    date_upload = uploadAt
                },
            )
        }

        return chapters
    }

    private fun parseUploadDateFromVersionInfo(doc: Document): Long? {
        for (info in doc.select("div.tab-content > div.info-card div.text")) {
            val date = dateRegex.find(info.text()) ?: continue
            return dateFormat.tryParse(date.value)
        }
        return null
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val document = Jsoup.parse(html)
        val requestUrl = response.request.url.toString()
        val isVideoChapter = requestUrl.contains("#video")

        val videoDomain = domainRegex.find(html)?.groupValues?.get(1)
            ?: "https://img.xchina.io"

        if (isVideoChapter) {
            // Return only videos
            val videoPages = videosRegex.find(html)?.let { match ->
                val jsonStr = match.groupValues[1]
                videoUrlRegex.findAll(jsonStr).map { it.groupValues[1] }.toList()
                    .mapIndexed { i, path ->
                        Page(
                            index = i,
                            url = "$videoDomain$path",
                            imageUrl = null,
                        )
                    }
            } ?: emptyList()
            return videoPages
        }

        // Return only images (for image chapters)
        val firstImageUrl = document.selectFirst(imageSelector)
            ?.let { parseUrlFormStyle(it.selectFirst("div.img")) }
        return document.select(imageSelector)
            .mapIndexed { i, imageElement ->
                val url = parseUrlFormStyle(imageElement.selectFirst("div.img"))!!
                Page(
                    index = i,
                    url = url,
                    imageUrl = url,
                )
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        Filters.createContentTypeSelector(intl),
        Filter.Separator(),
        Filters.createSortSelector(intl),
        Filter.Separator(),
        Filter.Header(intl["filter.header.ignored-when-search"]),
        Filters.createCategorySelector(intl),
    )
}
