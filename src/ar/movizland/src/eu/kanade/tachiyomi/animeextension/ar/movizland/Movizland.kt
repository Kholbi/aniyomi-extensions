package eu.kanade.tachiyomi.animeextension.ar.movizland

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.animeextension.ar.movizland.extractors.LinkboxExtractor
import eu.kanade.tachiyomi.animeextension.ar.movizland.extractors.MoshahdaExtractor
import eu.kanade.tachiyomi.animeextension.ar.movizland.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.ar.movizland.extractors.UQLoadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Movizland : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "موفيزلاند"

    override val baseUrl = "https://new.movizland.cyou/"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular
    private fun titleEdit(title: String, details: Boolean = false): String {
        return if (Regex("فيلم (.*?) مترجم").containsMatchIn(title))
            Regex("فيلم (.*?) مترجم").find(title)!!.groupValues[1] + " (فيلم)" // افلام اجنبيه مترجمه
        else if (Regex("فيلم (.*?) مدبلج").containsMatchIn(title))
            Regex("فيلم (.*?) مدبلج").find(title)!!.groupValues[1] + " (مدبلج)(فيلم)" // افلام اجنبيه مدبلجه
        else if (Regex("فيلم ([^a-zA-Z]+) ([0-9]+)").containsMatchIn(title)) // افلام عربى
            Regex("فيلم ([^a-zA-Z]+) ([0-9]+)").find(title)!!.groupValues[1] + " (فيلم)"
        else if (title.contains("مسلسل")) {
            if (title.contains("الموسم") and details) {
                val newTitle = Regex("مسلسل (.*?) الموسم (.*?) الحلقة ([0-9]+)").find(title)
                "${newTitle!!.groupValues[1]} (م.${newTitle.groupValues[2]})(${newTitle.groupValues[3]}ح)"
            } else if (title.contains("الحلقة")and details) {
                val newTitle = Regex("مسلسل (.*?) الحلقة ([0-9]+)").find(title)
                "${newTitle!!.groupValues[1]} (${newTitle.groupValues[2]}ح)"
            } else Regex(if (title.contains("الموسم")) "مسلسل (.*?) الموسم" else "مسلسل (.*?) الحلقة").find(title)!!.groupValues[1] + " (مسلسل)"
        } else if (title.contains("انمي"))
            return Regex(if (title.contains("الموسم"))"انمي (.*?) الموسم" else "انمي (.*?) الحلقة").find(title)!!.groupValues[1] + " (انمى)"
        else if (title.contains("برنامج"))
            Regex(if (title.contains("الموسم"))"برنامج (.*?) الموسم" else "برنامج (.*?) الحلقة").find(title)!!.groupValues[1] + " (برنامج)"
        else
            title
    }

    override fun popularAnimeSelector(): String = "div.BlocksInner div.BlocksUI div.BlockItem, div.BoxOfficeOtherSide div.BlocksUI div.BlockItem"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("a div.BlockImageItem img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = titleEdit(element.select("a div.BlockImageItem img").attr("alt"))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination li a.next"

    // episodes
    private fun seasonsNextPageSelector() = "div.BlockItem a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        fun addEpisodeNew(url: String, type: String, title: String = "") {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(url)
            if (type == "assembly")
                episode.name = title.replace("فيلم", "").trim()
            else if (type == "movie")
                episode.name = "watch"
            else if (Regex("الموسم (.*)").containsMatchIn(title))
                episode.name = Regex("الموسم (.*)").find(title)!!.value.replace("مترجمة", "").replace("والاخيرة", "").trim()
            else if (Regex("الحلقة (.*)").containsMatchIn(title))
                episode.name = Regex("الحلقة (.*)").find(title)!!.value.replace("مترجمة", "").replace("والاخيرة", "").trim()
            else if (Regex("حلقة (.*)").containsMatchIn(title))
                episode.name = Regex("حلقة (.*)").find(title)!!.value.replace("مترجمة", "").replace("والاخيرة", "").trim()
            else
                episode.name = title

            episodes.add(episode)
        }
        fun seasonsAdjust(selector: String, seasons: Elements): List<Element> {
            var reverse = false
            for ((i, s) in seasons.withIndex()) {
                if (s.select(selector).text().contains("الاول")) {
                    if (i == 0) reverse = true
                }
                if (s.select(selector).text().contains("الثان")) {
                    if (i != 0) {
                        seasons.remove(s)
                        seasons.add(1, s)
                    }
                }
            }
            return if (reverse) seasons.reversed() else seasons
        }
        fun addEpisodes(response: Response) {
            val document = response.asJsoup()
            val url = response.request.url.toString()
            // 1 episode in search to whole season

            if (url.contains("series")) {
                // Series and movie-series
                for (season in seasonsAdjust("div.BlockTitle", document.select(seasonsNextPageSelector()))) {
                    season.let {
                        val link = it.attr("href")
                        // if series > 1 season
                        if (link.contains("series")) {
                            val seasonHTML = client.newCall(GET(link, headers)).execute().asJsoup()
                            for (episode in seasonHTML.select(seasonsNextPageSelector())) {
                                episode.run {
                                    addEpisodeNew(this.attr("href"), "series", this.select("div.BlockTitle").text())
                                }
                            }
                        } else {
                            // if series 1 season only
                            val title = it.select("div.BlockTitle").text()
                            addEpisodeNew(link, if (title.contains("فيلم")) "assembly" else "series", title)
                        }
                    }
                }
            } else {
                // Movies
                var countSeasons = 0
                var count = 0
                for (season in seasonsAdjust("a", document.select("div.SeriesSingle ul.DropdownFilter li"))) {
                    countSeasons++
                    val seasonData = season.select("a").attr("data-term")
                    val refererHeaders = Headers.headersOf("referer", url, "x-requested-with", "XMLHttpRequest")
                    val requestBody = FormBody.Builder().add("season", seasonData).build()
                    val getEpisodes = client.newCall(POST("${baseUrl}wp-content/themes/Moviezland2022/EpisodesList.php", refererHeaders, requestBody)).execute().asJsoup()
                    for (episode in getEpisodes.select("div.EpisodeItem").reversed()) {
                        addEpisodeNew(episode.select("a").attr("href"), "series", season.select("a").text() + " " + episode.select("a").text())
                    }
                }
                if (countSeasons == 0) {
                    for (episode in document.select("div.EpisodeItem").reversed()) {
                        count++
                        addEpisodeNew(
                            episode.select("a").attr("href"),
                            "series",
                            document.select("div.SeriesSingle h2 span a").text() + " " + episode.select("a").text()
                        )
                    }
                    if (count == 0)
                        addEpisodeNew(url, "movie")
                }
            }
        }

        addEpisodes(response)

        return episodes
    }

    override fun episodeListSelector() = "link[rel=canonical]"

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val videos = mutableListOf<Video>()
        val document = response.asJsoup()
        for (server in document.select("div.ServersEmbeds ul code iframe")) {
            val link = server.attr("data-srcout")
            if (link.contains("moshahda")) {
                val refererHeaders = Headers.headersOf("referer", response.request.url.toString())
                val videosFromURL = MoshahdaExtractor(client).videosFromUrl(link, refererHeaders)
                videos.addAll(videosFromURL)
            } else if (link.contains("linkbox")) {
                val videosFromURL = LinkboxExtractor(client).videosFromUrl(link)
                videos.addAll(videosFromURL)
            } else if (link.contains("dood")) {
                val videosFromURL = DoodExtractor(client).videoFromUrl(link.replace("/d/", "/e/"))
                if (videosFromURL != null) videos.add(videosFromURL)
            } else if (link.contains("uqload")) {
                val videosFromURL = UQLoadExtractor(client).videoFromUrl(link, "Uqload: 720p")
                if (videosFromURL != null) videos.add(videosFromURL)
            } else if (link.contains("streamtape")) {
                val videosFromURL = StreamTapeExtractor(client).videoFromUrl(link)
                if (videosFromURL != null) videos.add(videosFromURL)
            }
        }
        return videos
    }

    override fun videoListSelector() = "body"

    private fun videosFromElement(element: Element): List<Video> {
        val videoList = mutableListOf<Video>()
        val qualityMap = mapOf("l" to "240p", "n" to "360p", "h" to "480p", "x" to "720p", "o" to "1080p")
        val data = element.data().substringAfter(", file: \"").substringBefore("\"}],")
        val url = Regex("(.*)_,(.*),\\.urlset/master(.*)").find(data)
        val sources = url!!.groupValues[2].split(",")
        for (quality in sources) {
            val src = url.groupValues[1] + "_" + quality + "/index-v1-a1" + url.groupValues[3]
            val video = qualityMap[quality]?.let {
                Video(src, it, src)
            }
            if (video != null) {
                videoList.add(video)
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoUrlParse(document: Document) = throw Exception("Stub")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val title = titleEdit(element.select("a div.BlockImageItem div.BlockTitle").text(), true)
        anime.thumbnail_url = element.select("a div.BlockImageItem img").attr("src")
        if (anime.thumbnail_url.isNullOrEmpty()) anime.thumbnail_url =
            element.select("a div.BlockImageItem img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = title
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination div.navigation ul li.active + li a"

    override fun searchAnimeSelector(): String = "div.BlocksInner div.BlocksUI div.BlockItem, div.BoxOfficeOtherSide div.BlocksUI div.BlockItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var newQuery = query
        if (Regex("(.*)\\(").containsMatchIn(query))
            newQuery = Regex("(.*)\\(").find(query)!!.groupValues[1].replace("(", "")
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$newQuery"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/category/$catQ/page/$page/"
                            return GET(catUrl, headers)
                        }
                    }
                }
            }
            throw Exception("اختر قسم")
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // div.CoverSingle div.CoverSingleContent
        anime.genre = document.select("div.SingleDetails li:contains(النوع) a,div.SingleDetails li:contains(الجودة) a").joinToString(", ") { it.text() }
        anime.title = if (document.select("h2.postTitle").isNullOrEmpty())
            titleEdit(document.select("div.H1Title h1").text()) else titleEdit(document.select("h2.postTitle").text())
        anime.author = document.select("div.SingleDetails li:contains(دولة) a").text()
        anime.description = document.select("div.ServersEmbeds section.story").text().replace(document.select("meta[property=og:title]").attr("content"), "").replace(":", "").trim()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)
    private data class CatUnit(val name: String, val query: String)
    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("كل الافلام", "movies"),
        CatUnit("افلام اجنبى", "movies/foreign"),
        CatUnit("افلام نتفلكس", "movies/netflix"),
        CatUnit("سلاسل افلام", "movies/backs"),
        CatUnit("افلام انمى", "movies/anime"),
        CatUnit("افلام اسيوى", "movies/asia"),
        CatUnit("افلام هندى", "movies/india"),
        CatUnit("افلام عربى", "movies/arab"),
        CatUnit("افلام تركى", "movies/turkey"),
        CatUnit("مسلسلات", "series"),
        CatUnit("مسلسلات اجنبى", "series/foreign-series"),
        CatUnit("مسلسلات نتفلكس", "series/netflix-series"),
        CatUnit("مسلسلات انمى", "series/anime-series"),
        CatUnit("مسلسلات تركى", "series/turkish-series"),
        CatUnit("مسلسلات عربى", "series/arab-series")
    )

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
