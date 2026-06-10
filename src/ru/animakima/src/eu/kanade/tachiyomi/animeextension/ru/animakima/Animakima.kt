package eu.kanade.tachiyomi.animeextension.ru.animakima

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject

class Animakima : ParsedAnimeHttpSource() {

    override val name: String = "Animakima"
    override val baseUrl: String = "https://animakima.ru"
    override val lang: String = "ru"
    override val supportsLatest: Boolean = true

    override val headers: Headers = super.headers.newBuilder()
        .add("Referer", "$baseUrl/")
        .build()

    companion object {
        /**
         * Карточки на /top/ и на /search/?q=...
         * Сейчас сайт рендерит их внутри .title-list как кастомные <card-title class="card scroll-item">.
         * Если верстка изменится — первым делом проверь этот селектор.
         */
        private const val POPULAR_AND_SEARCH_SELECTOR = ".title-list card-title.card"

        /**
         * На /ongoings/ используется другая сетка:
         * <div class="grid"><article><a class="compilation-card ..."></a></article></div>
         */
        private const val LATEST_SELECTOR = ".grid > article > a.compilation-card"

        /**
         * Эпизоды на странице тайтла лежат в выпадающем списке:
         * .chapters-select-list > button.btn--select-video[data-sid]
         */
        private const val EPISODE_SELECTOR = ".chapters-select-list .btn--select-video"

        /**
         * Fallback для одиночных тайтлов/фильмов, если полного списка кнопок нет.
         */
        private const val CURRENT_EPISODE_SELECTOR = ".btn--chapter-select[data-sid]"

        /**
         * Жанры на странице тайтла.
         */
        private const val GENRE_SELECTOR = ".tag-list .tag-link"

        /**
         * Основное описание тайтла.
         */
        private const val DESCRIPTION_SELECTOR = ".title-description .expanded-area"

        /**
         * Блок с meta-полями (год, режиссёр, страна, эпизоды и т.д.).
         */
        private const val META_LINE_SELECTOR = ".meta-block dl.meta-line"

        /**
         * На самой странице эпизода/тайтла иногда можно поймать прямой <video> или iframe.
         * Это fallback, основной путь — API get_download_link/get_player_view.
         */
        private const val VIDEO_FALLBACK_SELECTOR = "video[src], video source[src], iframe[src]"
    }

    // ==============================
    // Popular
    // ==============================

    override fun popularAnimeRequest(page: Int): Request {
        // У /top/ сейчас фактически одна большая страница, без нормальной пагинации.
        return GET("$baseUrl/top/", headers)
    }

    override fun popularAnimeSelector(): String = POPULAR_AND_SEARCH_SELECTOR

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromCardElement(element)

    override fun popularAnimeNextPageSelector(): String? = null

    // ==============================
    // Latest
    // ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/ongoings/"
        } else {
            "$baseUrl/ongoings/?p=$page"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = LATEST_SELECTOR

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("h3")?.text()?.trim().orEmpty()
            thumbnail_url = absoluteUrl(element.selectFirst("img")?.attr("src"))
            setUrlWithoutDomain(element.attr("href"))
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = ".pagination .btn--pagination--next"

    // ==============================
    // Search
    // ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isBlank()) {
            return popularAnimeRequest(page)
        }

        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .apply {
                if (page > 1) addQueryParameter("p", page.toString())
            }
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeSelector(): String = POPULAR_AND_SEARCH_SELECTOR

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromCardElement(element)

    override fun searchAnimeNextPageSelector(): String? = ".pagination .btn--pagination--next"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ==============================
    // Anime details
    // ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.title = document.selectFirst("h1")?.text()?.trim().orEmpty()

        // Самый стабильный источник полного постера сейчас — href у .btn--title-full-image
        anime.thumbnail_url = absoluteUrl(
            document.selectFirst(".btn--title-full-image")?.attr("href")
                ?: document.selectFirst(".title-card__actions picture img")?.attr("srcset")
                ?: document.selectFirst(".title-card__actions img")?.attr("src")
        )

        val description = document.select("$DESCRIPTION_SELECTOR p")
            .eachText()
            .joinToString("\n\n")
            .ifBlank {
                document.selectFirst(DESCRIPTION_SELECTOR)?.text()?.trim().orEmpty()
            }
            .ifBlank {
                // Fallback: если описание вынесли только в блок "Сюжет"
                document.select(".content.plot p").eachText().joinToString("\n\n")
            }

        anime.description = description

        anime.genre = document.select(GENRE_SELECTOR)
            .eachText()
            .joinToString(", ")

        val meta = parseMeta(document)

        // На сайте студия чаще всего не указана.
        // В author кладём режиссёра; если его нет/он "Неизвестно" — добавляем страну как полезный fallback.
        anime.author = buildList {
            meta["Режиссёр"]
                ?.takeUnless { it.equals("Неизвестно", ignoreCase = true) }
                ?.let { add(it) }

            meta["Страна"]?.let { add("Страна: $it") }
        }.takeIf { it.isNotEmpty() }?.joinToString(" • ")

        anime.status = parseAnimeStatus(
            explicitStatus = meta["Статус"],
            episodesText = meta["Эпизодов"],
        )

        anime.initialized = true
        return anime
    }

    // ==============================
    // Episodes
    // ==============================

    override fun episodeListSelector(): String = EPISODE_SELECTOR

    override fun episodeListParse(document: Document): List<SEpisode> {
        val episodeElements = document.select(EPISODE_SELECTOR).ifEmpty {
            document.select(CURRENT_EPISODE_SELECTOR)
        }

        // Aniyomi ожидает список в descending order относительно source order,
        // поэтому переворачиваем: последний эпизод будет первым в списке.
        return episodeElements
            .map { episodeFromElement(it) }
            .asReversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val sid = element.attr("data-sid").trim()
        val baseEpisodeUrl = element.ownerDocument()?.location()
            ?.removePrefix(baseUrl)
            ?.substringBefore("?")
            ?: "/"

        val episodeName = element.text().trim().ifBlank { "Эпизод $sid" }

        return SEpisode.create().apply {
            name = episodeName
            url = "$baseEpisodeUrl?sid=$sid"
            episode_number = Regex("""(\d+(?:\.\d+)?)""")
                .find(episodeName)
                ?.groupValues
                ?.getOrNull(1)
                ?.toFloatOrNull()
                ?: 0f
        }
    }

    // ==============================
    // Videos
    // ==============================

    override fun videoListSelector(): String = VIDEO_FALLBACK_SELECTOR

    override fun videoListParse(document: Document): List<Video> {
        val videos = mutableListOf<Video>()

        val sid = document.location().toHttpUrlOrNull()?.queryParameter("sid")
            ?: document.selectFirst(CURRENT_EPISODE_SELECTOR)?.attr("data-sid")
            ?: document.selectFirst(EPISODE_SELECTOR)?.attr("data-sid")

        val aid = document.selectFirst(".title-card")?.attr("data-aid")

        // Основной путь: у сайта есть API, которое сам фронтенд использует для плеера и ссылки скачивания.
        if (!sid.isNullOrBlank()) {
            getDownloadLink(sid)?.let { directUrl ->
                videos += Video(
                    url = directUrl,
                    quality = "Direct MP4",
                    videoUrl = directUrl,
                    headers = headers,
                )
            }

            if (!aid.isNullOrBlank()) {
                getPlayerEmbed(aid, sid)?.let { (embedUrl, playerName) ->
                    videos += Video(
                        url = embedUrl,
                        quality = "${playerName.ifBlank { "Auto" }}",
                        videoUrl = embedUrl,
                        headers = headers,
                    )
                }
            }
        }

        // Fallback: если API изменили, пробуем собрать прямые видео/iframe прямо из DOM.
        document.select(videoListSelector()).forEach { element ->
            runCatching { videoFromElement(element) }
                .getOrNull()
                ?.let { videos += it }
        }

        return videos
            .distinctBy { it.videoUrl.ifBlank { it.url } }
            .ifEmpty { emptyList() }
    }

    override fun videoFromElement(element: Element): Video {
        val src = absoluteUrl(
            when (element.tagName()) {
                "video", "source", "iframe" -> element.attr("src")
                else -> element.attr("src")
            },
        )

        val quality = when {
            src.contains("rutube", ignoreCase = true) -> "Rutube (Auto)"
            src.contains(".m3u8", ignoreCase = true) -> "HLS"
            src.contains(".mp4", ignoreCase = true) -> "MP4"
            element.tagName() == "iframe" -> "Iframe (Auto)"
            else -> "Default"
        }

        return Video(
            url = src,
            quality = quality,
            videoUrl = src,
            headers = headers,
        )
    }

    // ==============================
    // Helpers
    // ==============================

    private fun animeFromCardElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst(".card-link")?.text()?.trim()
                ?: element.selectFirst("h3")?.text()?.trim()
                ?: "Без названия"

            thumbnail_url = absoluteUrl(
                element.selectFirst(".card-image")?.attr("src")
                    ?: element.selectFirst("img")?.attr("src"),
            )

            val href = element.selectFirst(".card-link")?.attr("href")
                ?: element.selectFirst("a[href]")?.attr("href")
                ?: ""

            setUrlWithoutDomain(href)
        }
    }

    private fun parseMeta(document: Document): Map<String, String> {
        val result = linkedMapOf<String, String>()

        document.select(META_LINE_SELECTOR).forEach { line ->
            val key = line.select("dt").eachText()
                .joinToString(" ")
                .replace(":", "")
                .trim()

            val value = line.select("dd").eachText()
                .joinToString(" ")
                .trim()

            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }

        return result
    }

    private fun parseAnimeStatus(explicitStatus: String?, episodesText: String?): Int {
        explicitStatus?.lowercase()?.let { status ->
            return when {
                "ongoing" in status || "онгоинг" in status -> SAnime.ONGOING
                "full" in status || "completed" in status || "film" in status || "ova" in status -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }

        episodesText?.let { text ->
            if ("+" in text) return SAnime.ONGOING

            val match = Regex("""(\d+)\s*из\s*(\d+)""").find(text)
            if (match != null) {
                val current = match.groupValues[1].toIntOrNull()
                val total = match.groupValues[2].toIntOrNull()

                if (current != null && total != null) {
                    return if (current < total) SAnime.ONGOING else SAnime.COMPLETED
                }
            }
        }

        return SAnime.UNKNOWN
    }

    /**
     * Универсальный helper для API сайта.
     *
     * Фронтенд сайта шлёт POST на /api/ с form-data:
     * action=get_download_link / get_player_view / ...
     */
    private fun apiRequest(vararg params: Pair<String, String>): JSONObject {
        val formBody = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
        }.build()

        val request = POST(
            "$baseUrl/api/",
            headers.newBuilder()
                .add("Referer", "$baseUrl/")
                .build(),
            formBody,
        )

        client.newCall(request).execute().use { response ->
            return JSONObject(response.body.string())
        }
    }

    /**
     * Даёт прямую ссылку на mp4.
     * Это лучший вариант для Aniyomi, потому что он воспроизводится напрямую.
     */
    private fun getDownloadLink(sid: String): String? {
        return runCatching {
            val json = apiRequest(
                "action" to "get_download_link",
                "sid" to sid,
            )

            if (!json.optBoolean("success")) return null

            json.optString("data")
                .takeUnless { it.isBlank() || it == "null" }
        }.getOrNull()
    }

    /**
     * Даёт HTML фрагмент плеера; на текущем сайте там обычно iframe.
     */
    private fun getPlayerEmbed(aid: String, sid: String): Pair<String, String>? {
        return runCatching {
            val json = apiRequest(
                "action" to "get_player_view",
                "aid" to aid,
                "type" to "main",
                "sid" to sid,
            )

            if (!json.optBoolean("success")) return null

            val html = json.optString("data")
            val playerName = json.optString("player")
                .takeUnless { it.isBlank() || it == "null" }
                ?.replaceFirstChar { ch -> ch.uppercase() }

            val embedDoc = Jsoup.parse(html, baseUrl)

            val src = embedDoc.selectFirst("iframe[src], video[src], source[src]")?.let {
                when (it.tagName()) {
                    "iframe", "video", "source" -> absoluteUrl(it.attr("src"))
                    else -> null
                }
            }

            if (src.isNullOrBlank()) null else (src to (playerName ?: "Auto"))
        }.getOrNull()
    }

    private fun absoluteUrl(rawUrl: String?): String {
        val clean = rawUrl
            .orEmpty()
            .trim()
            .substringBefore(" ")
            .ifBlank { return "" }

        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> baseUrl + clean
            else -> baseUrl.trimEnd('/') + "/" + clean.trimStart('/')
        }
    }
}
