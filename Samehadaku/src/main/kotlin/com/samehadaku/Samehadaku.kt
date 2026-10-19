package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        "daftar-anime-2/page/%d/?status=Currently+Airing&order=popular" to "Ongoing Populer",
        "daftar-anime-2/page/%d/?status=Completed&order=popular" to "Completed Populer",
        "daftar-anime-2/page/%d/?type=Movie&order=update" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = getDoc("$mainUrl/${request.data.format(page)}")
        val items = doc.select("div.post-show ul li").mapNotNull { it.toLatest() } +
                doc.select("article.animepost").mapNotNull { it.toCard() }
        return newHomePageResponse(request.name, items)
    }

    // "Episode Terbaru" rows: the title link already points at the series page.
    private fun Element.toLatest(): SearchResponse? {
        val a = selectFirst("h2.entry-title a") ?: return null
        val ep = selectFirst("span:has(b:containsOwn(Episode))")?.ownText()?.trim()?.toIntOrNull()
        return newAnimeSearchResponse(a.text().trim(), a.attr("href"), TvType.Anime) {
            posterUrl = selectFirst("img")?.attr("src")
            addSub(ep)
        }
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val title = selectFirst("h2")?.text()?.trim() ?: a.attr("title")
        return newAnimeSearchResponse(title, toSeriesUrl(a.attr("href")), TvType.Anime) {
            posterUrl = selectFirst("img")?.attr("src")
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

        val type = when {
            info("Type")?.contains("Movie", true) == true -> TvType.AnimeMovie
            info("Type")?.contains("OVA", true) == true -> TvType.OVA
            else -> TvType.Anime
        }

        // shortcut: the series page only lists the latest 100 episodes, older ones are not reachable from it.
        val episodes = doc.select("div.custom-scrollbar h4 a").map {
            val href = it.attr("href")
            newEpisode(href) {
                name = it.text().trim()
                episode = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.distinctBy { it.data }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("div.entry-content")?.text()?.trim()
            tags = doc.select("a[href*=/genre/]").map { it.text().trim() }.distinct()
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
