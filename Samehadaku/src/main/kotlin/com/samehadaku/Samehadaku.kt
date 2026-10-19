package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = "https://v2.samehadaku.how"
    override var name = "Samehadaku"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // The site sits behind a Cloudflare challenge; this solves it in a WebView once and reuses the cookie.
    private val cfKiller by lazy { CloudflareKiller() }

    private suspend fun getDoc(url: String): Document = app.get(url, interceptor = cfKiller).document

    override val mainPage = mainPageOf(
        "anime-terbaru/page/%d/" to "Episode Terbaru",
        "jadwal-rilis/" to "Anime Musim Ini",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=popular" to "Ongoing Populer",
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=latest" to "Ongoing Terbaru Ditambahkan",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=popular" to "Completed Populer",
        "daftar-anime-2/page/%d/?status=Finished+Airing&order=update" to "Completed Terbaru",
        "daftar-anime-2/page/%d/?type=Movie&order=update" to "Movie",
        "daftar-anime-2/page/%d/?type=OVA&order=update" to "OVA",
        "daftar-anime-2/page/%d/?type=Special&order=update" to "Special",
        // The site's own /genre/ pages ignore the genre, but the anime list filter honours it.
        "daftar-anime-2/page/%d/?genre[]=action&order=popular" to "Action",
        "daftar-anime-2/page/%d/?genre[]=adventure&order=popular" to "Adventure",
        "daftar-anime-2/page/%d/?genre[]=comedy&order=popular" to "Comedy",
        "daftar-anime-2/page/%d/?genre[]=fantasy&order=popular" to "Fantasy",
        "daftar-anime-2/page/%d/?genre[]=isekai&order=popular" to "Isekai",
        "daftar-anime-2/page/%d/?genre[]=romance&order=popular" to "Romance",
        "daftar-anime-2/page/%d/?genre[]=school&order=popular" to "School",
        "daftar-anime-2/page/%d/?genre[]=slice-of-life&order=popular" to "Slice of Life",
        "daftar-anime-2/page/%d/?genre[]=sports&order=popular" to "Sports",
        "daftar-anime-2/page/%d/?genre[]=mystery&order=popular" to "Mystery",
        "daftar-anime-2/page/%d/?genre[]=sci-fi&order=popular" to "Sci-Fi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The release schedule is a single page of everything airing this season.
        if (request.data == "jadwal-rilis/") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val cards = getDoc("$mainUrl/jadwal-rilis/").select("div.animposx").mapNotNull { it.toCard() }.distinctBy { it.url }
            return newHomePageResponse(request.name, cards, false)
        }
        val doc = getDoc("$mainUrl/${request.data.format(page)}")
        val latest = doc.select("div.post-show ul li").mapNotNull { it.toLatest() }
        // Latest-episode thumbnails are 16:9 screenshots, so show that row with wide cards.
        if (latest.isNotEmpty()) return newHomePageResponse(HomePageList(request.name, latest, true))
        return newHomePageResponse(request.name, doc.select("article.animepost").mapNotNull { it.toCard() })
    }

    // WordPress serves resized copies like "Foo-213x300.jpg"; the original is much sharper.
    private fun fullImage(url: String?) = url?.replace(Regex("""-\d+x\d+(\.\w+)$"""), "$1")

    private fun typeOf(text: String?) = when {
        text == null -> TvType.Anime
        text.contains("Movie", true) -> TvType.AnimeMovie
        text.contains("OVA", true) || text.contains("ONA", true) || text.contains("Special", true) -> TvType.OVA
        else -> TvType.Anime
    }

    // "Episode Terbaru" rows: the title link already points at the series page.
    private fun Element.toLatest(): SearchResponse? {
        val a = selectFirst("h2.entry-title a") ?: return null
        val ep = selectFirst("span:has(b:containsOwn(Episode))")?.ownText()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(a.text().trim(), a.attr("href"), TvType.Anime) {
            posterUrl = fullImage(selectFirst("img")?.attr("src"))
            addSub(ep)
        }
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst("h2")?.text()?.trim() ?: a.attr("title")
        return newAnimeSearchResponse(title, toSeriesUrl(a.attr("href")), typeOf(selectFirst("div.type")?.text())) {
            posterUrl = fullImage(selectFirst("img")?.attr("src"))
            score = Score.from10(selectFirst("div.score")?.text()?.let { Regex("""\d+(\.\d+)?""").find(it)?.value })
        }
    }

    // Search also returns single-episode posts like /kanata-kara-episode-3/; map them to /anime/kanata-kara/.
    private fun toSeriesUrl(url: String): String {
        if (url.contains("/anime/")) return url
        val slug = Regex("""/([^/]+?)-episode-\d+""").find(url)?.groupValues?.get(1) ?: return url
        return "$mainUrl/anime/$slug/"
    }

    override suspend fun search(query: String): List<SearchResponse> =
        getDoc("$mainUrl/?s=$query").select("main#main article.animepost, article.animepost")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }

    override suspend fun load(url: String): LoadResponse {
        var doc = getDoc(url)
        // Fallback when a mapped/episode URL lands on an episode page instead of the series page.
        if (doc.select("div.custom-scrollbar h4 a").isEmpty()) {
            doc.selectFirst("a[href*=/anime/]")?.attr("href")?.let { doc = getDoc(it) }
        }

        val title = doc.selectFirst("h1")?.text()?.removeSuffix("Sub Indo")?.trim() ?: name
        fun info(label: String) = doc.select("span.w-28")
            .firstOrNull { it.text().trim().equals(label, true) }?.nextElementSibling()?.text()?.trim()

        val type = typeOf(info("Type"))

        // shortcut: the series page only lists the latest 100 episodes, older ones are not reachable from it.
        val episodes = doc.select("div.custom-scrollbar h4 a").map {
            val href = it.attr("href")
            newEpisode(href) {
                name = it.text().trim()
                episode = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = doc.selectFirst("img.object-cover")?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("div.entry-content")?.text()?.trim()
            engName = info("English")
            japName = info("Japanese")
            // The score is the only leaf element on the page whose whole text is a number like "6.46".
            score = Score.from10(doc.select("span, div").firstOrNull { it.children().isEmpty() && it.text().matches(Regex("""\d\.\d{1,2}""")) }?.text())
            addDuration(info("Duration"))
            // Only the first genre block belongs to this anime; the sidebar has its own genre links.
            tags = doc.selectFirst("a[href*=/genre/]")?.parent()?.select("a[href*=/genre/]")?.map { it.text().trim() }
            showStatus = when {
                info("Status")?.contains("Completed", true) == true -> ShowStatus.Completed
                info("Status")?.contains("Ongoing", true) == true ||
                        info("Status")?.contains("Airing", true) == true -> ShowStatus.Ongoing
                else -> null
            }
            year = info("Released")?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)

        // Download mirrors, one <li> per quality. These play in CloudStream; the Mega/Blogger players mostly don't.
        doc.select("li:has(div.download-eps-links)").amap { row ->
            // Rows are grouped under a heading like "MKV", "MP4" or "x265 [...]".
            val format = row.closest("div.download-eps")?.selectFirst("p b")?.text()?.substringBefore(" ").orEmpty()
            val label = row.selectFirst("strong")?.text()?.trim().orEmpty()
            val quality = when (label.uppercase()) {
                "MP4HD" -> "720p"
                "FULLHD" -> "1080p"
                "4K" -> "2160p"
                else -> label
            }
            row.select("div.download-eps-links a[href]").amap { a ->
                val href = a.attr("href")
                val pixeldrain = Regex("""pixeldrain\.com/u/(\w+)""").find(href)?.groupValues?.get(1)
                when {
                    // Pixeldrain's API serves the file itself, so no extractor round-trip is needed.
                    pixeldrain != null -> callback(
                        newExtractorLink("Pixeldrain", "Pixeldrain $format $label","https://pixeldrain.com/api/file/$pixeldrain", ExtractorLinkType.VIDEO) {
                            this.quality = getQualityFromName(quality)
                        }
                    )
                    // RAR archives can't be played.
                    href.contains("mediafire", true) -> {}
                    else -> loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }

        doc.select("div#server ul li div.player-server-btn").amap { server ->
            // Servers carry the iframe in data-embed; older posts use the theme's player_ajax endpoint instead.
            val embed = server.attr("data-embed").ifBlank {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to server.attr("data-post"),
                        "nume" to server.attr("data-nume"),
                        "type" to server.attr("data-type"),
                    ),
                    referer = data,
                    interceptor = cfKiller,
                ).text
            }
            val src = Jsoup.parse(embed).selectFirst("iframe")?.attr("src") ?: return@amap
            loadExtractor(src, data, subtitleCallback, callback)
        }
        return true
    }
}
