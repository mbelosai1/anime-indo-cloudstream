package com.otakudesu

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "ongoing-anime/page/%d/" to "Anime Ongoing",
        "complete-anime/page/%d/" to "Anime Complete",
        "genres/action/page/%d/" to "Action",
        "genres/adventure/page/%d/" to "Adventure",
        "genres/comedy/page/%d/" to "Comedy",
        "genres/fantasy/page/%d/" to "Fantasy",
        "genres/romance/page/%d/" to "Romance",
        "genres/school/page/%d/" to "School",
        "genres/slice-of-life/page/%d/" to "Slice of Life",
        "genres/sports/page/%d/" to "Sports",
        "genres/mystery/page/%d/" to "Mystery",
        "genres/sci-fi/page/%d/" to "Sci-Fi",
        "genres/supernatural/page/%d/" to "Supernatural",
        "genres/horror/page/%d/" to "Horror",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data.format(page)}").document
        // Ongoing/complete lists use "detpost" cards, genre pages use "col-anime" rows.
        val items = doc.select("div.detpost").mapNotNull { it.toCard() } +
                doc.select("div.col-anime").mapNotNull { it.toGenreCard() }
        return newHomePageResponse(request.name, items)
    }

    private fun scoreOf(text: String?) = Score.from10(text?.let { Regex("""\d+(\.\d+)?""").find(it)?.value })

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("div.thumb a") ?: return null
        val title = selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val ep = selectFirst("div.epz")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
        return newAnimeSearchResponse(title, a.attr("href"), TvType.Anime) {
            posterUrl = selectFirst("img")?.attr("src")
            addSub(ep)
            // On the complete list this slot holds the score; on the ongoing list it holds the airing day.
            score = scoreOf(selectFirst("div.epztipe")?.text())
        }
    }

    private fun Element.toGenreCard(): SearchResponse? {
        val a = selectFirst("div.col-anime-title a") ?: return null
        return newAnimeSearchResponse(a.text().trim(), a.attr("href"), TvType.Anime) {
            posterUrl = selectFirst("div.col-anime-cover img")?.attr("src")
            score = scoreOf(selectFirst("div.col-anime-rating")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/", params = mapOf("s" to query, "post_type" to "anime")).document
            .select("ul.chivsrc li").mapNotNull {
                val a = it.selectFirst("h2 a") ?: return@mapNotNull null
                newAnimeSearchResponse(a.text().removeSuffix("Subtitle Indonesia").trim(), a.attr("href"), TvType.Anime) {
                    posterUrl = it.selectFirst("img")?.attr("src")
                }
            }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        // Info rows look like "<b>Status</b>: Ongoing".
        fun info(label: String) = doc.select("div.infozingle p span")
            .firstOrNull { it.selectFirst("b")?.text()?.trim().equals(label, true) }
            ?.text()?.substringAfter(":")?.trim()?.ifBlank { null }

        val title = info("Judul") ?: doc.selectFirst("h1")?.text()?.trim() ?: name
        val type = when {
            info("Tipe")?.contains("Movie", true) == true -> TvType.AnimeMovie
            info("Tipe")?.contains("OVA", true) == true || info("Tipe")?.contains("Special", true) == true -> TvType.OVA
            else -> TvType.Anime
        }

        val episodes = doc.select("div.episodelist ul li span a[href*=/episode/]").map {
            val href = it.attr("href")
            newEpisode(href) {
                name = it.text().replace(title, "").replace("Subtitle Indonesia", "").trim().ifBlank { null }
                episode = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = doc.selectFirst("div.fotoanime img")?.attr("src")
            plot = doc.select("div.sinopc p").joinToString("\n\n") { it.text() }.ifBlank { null }
            tags = doc.select("div.infozingle a[href*=/genres/]").map { it.text().trim() }
            japName = info("Japanese")
            score = scoreOf(info("Skor"))
            showStatus = when {
                info("Status")?.contains("Completed", true) == true -> ShowStatus.Completed
                info("Status")?.contains("Ongoing", true) == true -> ShowStatus.Ongoing
                else -> null
            }
            year = info("Tanggal Rilis")?.let { Regex("""\d{4}""").find(it)?.value?.toIntOrNull() }
            addDuration(info("Durasi"))
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun b64(s: String) = String(Base64.decode(s, Base64.DEFAULT))

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val ajax = "$mainUrl/wp-admin/admin-ajax.php"

        // Streaming mirrors: each button holds base64 JSON {"id":..,"i":..,"q":"720p"} that the site
        // trades for an iframe through two admin-ajax calls (the action names come from the page's JS).
        val nonce = Regex(""""data":"([^"]+)"""").find(
            app.post(ajax, data = mapOf("action" to "aa1208d27f29ca340c92c66d1926f13f"), referer = data).text
        )?.groupValues?.get(1)
        if (nonce != null) {
            doc.select("div.mirrorstream a[data-content]").amap { a ->
                val payload = b64(a.attr("data-content"))
                fun field(k: String) = Regex(""""$k":"?([^",}]+)""").find(payload)?.groupValues?.get(1)
                val quality = field("q") ?: ""
                val res = app.post(
                    ajax, referer = data, data = mapOf(
                        "action" to "2a3505c93b0035d3f455df82bf976b84", "nonce" to nonce,
                        "id" to (field("id") ?: return@amap), "i" to (field("i") ?: return@amap), "q" to quality,
                    )
                ).text
                val html = Regex(""""data":"([^"]+)"""").find(res)?.groupValues?.get(1)?.let { b64(it) } ?: return@amap
                val src = Jsoup.parse(html).selectFirst("iframe")?.attr("src") ?: return@amap
                streamFromIframe(src, a.text().trim(), quality, data, subtitleCallback, callback)
            }
        }

        // Download mirrors go through link.desustream.com redirects to Pixeldrain, GoFile, Acefile, etc.
        doc.select("div.download ul li").amap { row ->
            val label = row.selectFirst("strong")?.text()?.trim().orEmpty()
            row.select("a[href]").amap { a ->
                val target = app.get(a.attr("href"), allowRedirects = false).headers["location"] ?: return@amap
                val pixeldrain = Regex("""pixeldrain\.com/u/(\w+)""").find(target)?.groupValues?.get(1)
                when {
                    pixeldrain != null -> callback(
                        newExtractorLink("Pixeldrain", "Pixeldrain $label", "https://pixeldrain.com/api/file/$pixeldrain", ExtractorLinkType.VIDEO) {
                            quality = getQualityFromName(label)
                        }
                    )
                    // Mega needs in-browser decryption, so CloudStream can't play it.
                    target.contains("mega.nz") -> {}
                    else -> loadExtractor(target, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun streamFromIframe(
        src: String, server: String, quality: String, referer: String,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        when {
            // desustream pages embed a direct MP4 (Google Video or archive.org) in the player config.
            src.contains("desustream") -> {
                val page = app.get(src, referer = "$mainUrl/").text
                val video = Regex("""https?://[^"'\s]+(?:\.mp4|videoplayback)[^"'\s]*""").find(page)?.value
                    ?.replace("&amp;", "&") ?: return
                callback(
                    newExtractorLink(name, "$name $server $quality", video, ExtractorLinkType.VIDEO) {
                        this.referer = src
                        this.quality = getQualityFromName(quality)
                    }
                )
            }
            // odvidhide.com is VidHide on its own domain, which loadExtractor doesn't recognise.
            src.contains("vidhide") -> VidHidePro().getUrl(src, referer, subtitleCallback, callback)
            src.contains("mega.nz") -> {}
            else -> loadExtractor(src, referer, subtitleCallback, callback)
        }
    }
}
