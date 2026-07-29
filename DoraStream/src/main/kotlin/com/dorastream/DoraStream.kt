package com.dorastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class DoraStream : MainAPI() {
    override var mainUrl = "https://dorabash.in"
    override var name = "DoraStream"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // All four sections, confirmed live. Pagination is the standard WP
    // pattern: page 1 has no suffix, page 2+ gets /page/N/.
    override val mainPage = mainPageOf(
        "anime-type/tv/" to "Seasons",
        "anime-type/movie/" to "Movies",
        "anime-type/specials/" to "Specials",
        "anime-type/short-movie/" to "Short Movies",
    )

    // dorabash.in sits behind Cloudflare with an interactive challenge, so
    // CloudflareKiller (headless WebView, can't click a checkbox) isn't
    // enough. cfSafeGet() (see CloudflareBypassInterceptor.kt) handles the
    // full flow: fast-path if not blocked, mutex-guarded solve-and-retry if
    // blocked - so concurrent calls (e.g. all 4 home page tabs loading at
    // once) share one dialog instead of each popping their own.
    private suspend fun getDocument(url: String): org.jsoup.nodes.Document {
        val response = cfSafeGet(url) { u -> app.get(u, interceptor = CloudflareBypassInterceptor) }
        return response.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        val document = getDocument(url)
        val home = document.select("article.anime-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, home),
            hasNext = home.isNotEmpty()
        )
    }

    // Listing cards already carry everything needed - no extra request per
    // card required (the old fork fetched each card's page again just to
    // resolve a "series" URL; that page turned out to no longer be needed).
    private fun Element.toSearchResult(): SearchResponse? {
        val titleAnchor = this.selectFirst("h3 a") ?: return null
        val title = titleAnchor.attr("title").ifBlank { titleAnchor.text() }.trim()
        if (title.isBlank()) return null

        val href = fixUrl(titleAnchor.attr("href"))
        val posterUrl = this.selectFirst("img")?.extractImageUrl()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.posterHeaders = cfHeaders(mainUrl)
        }
    }

    // Same fallback order confirmed against the site's actual working
    // provider: data-src, then data-lazy-src, then the first URL in
    // srcset (it's a space-separated "url descriptor" pair, so take
    // everything before the first space), then finally plain src as a
    // last resort. This site does not use data-original - that was a
    // guess on my part and never matched anything here, which is why
    // images fell through to the blank placeholder in src.
    private fun Element.extractImageUrl(): String? {
        val dataSrc = this.attr("data-src")
        if (dataSrc.isNotBlank()) return finishImageUrl(dataSrc)

        val dataLazySrc = this.attr("data-lazy-src")
        if (dataLazySrc.isNotBlank()) return finishImageUrl(dataLazySrc)

        val srcsetFirst = this.attr("srcset").substringBefore(" ")
        if (srcsetFirst.isNotBlank()) return finishImageUrl(srcsetFirst)

        val src = this.attr("src")
        return finishImageUrl(src)
    }

    private fun finishImageUrl(value: String): String? {
        if (value.isBlank() || value.startsWith("data:image")) return null
        return fixUrl(value)
    }

    // Standard WordPress search (confirmed via the site's own schema.org
    // SearchAction: "https://dorabash.in/?s={query}"). The site's own
    // "/search/?s_keyword=..." advanced search box is loaded entirely
    // client-side via a REST call and can't be scraped as HTML.
    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDocument("$mainUrl/?s=$query")
        return document.select("article.anime-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val title = document.selectFirst(".anime-data h4 a")?.attr("title")
            ?: document.selectFirst("h1")?.text().orEmpty()

        val poster = document.selectFirst(".anime-featured img")?.extractImageUrl()
        val synopsis = document.selectFirst(".anime-synopsis p")?.text()?.trim()
        val tags = document.select(".anime-metadata span").map { it.text().trim() }
            .filter { it.isNotBlank() }
        val rating = document.selectFirst(".anime-score-counts span:eq(1)")?.text()?.trim()

        // Episodes are listed directly in the page HTML now - no AJAX call
        // needed (the site used to paginate this via an admin-ajax.php
        // endpoint; that endpoint and its container no longer exist on the
        // current layout, confirmed against a live page with 15 episodes
        // all present in one shot).
        val episodeElements = document.select(".episode-list-item")

        if (episodeElements.isEmpty()) {
            // No episode list => movie / special / short movie, the watch
            // page itself is the playable entry.
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                posterHeaders = cfHeaders(mainUrl)
                plot = synopsis
                this.tags = tags
                rating?.toDoubleOrNull()?.let { score = Score.from10(it) }
            }
        }

        val episodes = episodeElements.mapNotNull { ep ->
            val epUrl = fixUrl(ep.attr("href"))
            if (epUrl.isBlank()) return@mapNotNull null

            val epNum = ep.attr("data-episode-search-query").toIntOrNull()
                ?: ep.selectFirst(".episode-list-item-number")?.text()?.trim()?.toIntOrNull()
            val epTitle = ep.selectFirst(".episode-list-item-title")?.text()?.trim()

            newEpisode(epUrl) {
                this.episode = epNum
                this.name = epTitle?.ifBlank { null }
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            posterHeaders = cfHeaders(mainUrl)
            plot = synopsis
            this.tags = tags
            rating?.toDoubleOrNull()?.let { score = Score.from10(it) }
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getDocument(data)
        var foundAny = false

        // Every server (sub + each dub language) is exposed as
        // data-embed-id="base64(label):base64(full iframe url)" on a <span>.
        // The label text itself always ends in "sub" or "dub" - no need for
        // a wrapping container to tell them apart.
        document.select("span[data-embed-id]").forEach { span ->
            val raw = span.attr("data-embed-id")
            val parts = raw.split(":")
            if (parts.size != 2) return@forEach

            // parts[0] is the visible label (e.g. "Japanese Sub", "Hindi Dub") -
            // decode it so we can stamp it onto the link name below, otherwise
            // every source shows up as an indistinguishable "Abyss [H264] 1080p".
            val label = runCatching { decodeBase64(parts[0]) }.getOrNull()?.trim()
            val embedUrl = runCatching { decodeBase64(parts[1]) }.getOrNull()
            if (embedUrl.isNullOrBlank()) return@forEach

            foundAny = true
            // loadExtractor will silently do nothing for a host CloudStream
            // has no matching extractor for - see the note below about
            // AbyssPlayer/Hydrax specifically.
            loadExtractor(embedUrl, data, subtitleCallback) { link ->
                val renamed = if (label.isNullOrBlank()) {
                    link
                } else {
                    @Suppress("DEPRECATION")
                    ExtractorLink(
                        source = link.source,
                        name = "${link.name} - $label",
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        headers = link.headers,
                        extractorData = link.extractorData,
                        type = link.type,
                        audioTracks = link.audioTracks
                    )
                }
                callback(renamed)
            }
        }

        return foundAny
    }

    private fun decodeBase64(input: String): String {
        return String(android.util.Base64.decode(input, android.util.Base64.DEFAULT))
    }
}// build trigger