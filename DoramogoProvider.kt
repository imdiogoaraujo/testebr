package com.doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

// ============================================================
//  DoramogoProvider — CloudStream 3 Extension
//  Site: https://www.doramogo.net/
//  Conteúdo: Doramas (séries asiáticas) em português
// ============================================================

class DoramogoProvider : MainAPI() {

    // ----------------------------------------------------------
    // 1. INFORMAÇÕES BÁSICAS DO PROVIDER
    // ----------------------------------------------------------

    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt"   // Português

    // Tipos de conteúdo que o site oferece
    override val supportedTypes = setOf(
        TvType.TvSeries,   // Séries / Doramas com episódios
        TvType.Movie       // Filmes (o site tem alguns)
    )

    // ----------------------------------------------------------
    // 2. PÁGINA INICIAL (Home Page)
    //    Define as categorias que aparecem na tela inicial
    // ----------------------------------------------------------

    override val mainPage = mainPageOf(
        // Formato: Pair("URL ou parâmetro", "Nome exibido no app")
        Pair("$mainUrl/episodios",          "Episódios Recentes"),
        Pair("$mainUrl/dorama",             "Todos os Doramas"),
        Pair("$mainUrl/genero/dorama-drama","Drama"),
        Pair("$mainUrl/genero/dorama-romance", "Romance"),
        Pair("$mainUrl/genero/dorama-comedia", "Comédia"),
        Pair("$mainUrl/genero/dorama-acao", "Ação"),
        Pair("$mainUrl/genero/dorama-fantasia", "Fantasia"),
    )

    // getMainPage é chamado toda vez que o usuário abre uma categoria
    // ou rola a tela para baixo (paginação automática)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // Monta a URL com paginação: página 1, 2, 3...
        val url = if (page == 1) request.data
                  else "${request.data}/page/$page"

        val document = app.get(url).document

        // Seleciona os cards de séries na página
        // ATENÇÃO: se o site mudar o layout, atualize este seletor CSS
        val items = document.select("article.w_item_b, div.item, article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items)
    }

    // ----------------------------------------------------------
    // 3. BUSCA
    //    O usuário digita algo no app → chama esta função
    // ----------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // A URL de busca do Doramogo é: /search/?q=TERMO
        val url = "$mainUrl/search/?q=${query.encodeUrl()}"
        val document = app.get(url).document

        return document.select("article.w_item_b, div.item, article")
            .mapNotNull { it.toSearchResult() }
    }

    // ----------------------------------------------------------
    // FUNÇÃO AUXILIAR: converte um elemento HTML em SearchResponse
    // Reutilizada tanto em getMainPage quanto em search()
    // ----------------------------------------------------------

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        // Pega o link e título do card
        val titleEl = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(titleEl.attr("href"))
        val title = this.selectFirst("h3, h2, .title, .nome")?.text()
                    ?: titleEl.attr("title").ifBlank { return null }

        // Imagem do poster (tenta data-src antes de src, por lazy-load)
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ----------------------------------------------------------
    // 4. PÁGINA DE DETALHES DA SÉRIE
    //    Chamada quando o usuário clica em um título
    // ----------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // --- Metadados ---
        val title = document.selectFirst("h1, .title_dorama, .entry-title")
            ?.text() ?: "Sem título"

        val poster = document.selectFirst("div.poster img, img.poster, img.capa")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }

        val plot = document.selectFirst("div.sinopse, div.synopsis, .description, p.desc")
            ?.text()

        val year = document.selectFirst(".ano, .year, span[class*=year]")
            ?.text()?.trim()?.toIntOrNull()

        val tags = document.select("a[href*='/genero/']")
            .map { it.text() }
            .filter { it.isNotBlank() }

        val rating = document.selectFirst(".nota, .rating, span[class*=imdb]")
            ?.text()?.toRatingInt()

        // --- Episódios ---
        // Busca todos os links de episódios na página
        val episodes = document.select("a[href*='/episodio/'], a[href*='/ep-'], ul.episodios li a")
            .mapNotNull { epEl ->
                val epUrl = fixUrl(epEl.attr("href"))
                if (epUrl.isBlank()) return@mapNotNull null

                // Tenta extrair número de temporada e episódio do texto
                val epText = epEl.text()
                val seasonNum = Regex("""[Tt](\d+)""").find(epText)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = Regex("""[Ee][Pp]?\.?\s*(\d+)""").find(epText)
                    ?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epText.ifBlank { null }
                    this.season = seasonNum
                    this.episode = epNum
                }
            }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.rating = rating
        }
    }

    // ----------------------------------------------------------
    // 5. CARREGAMENTO DOS LINKS DE VÍDEO
    //    A parte mais importante — obtém o link real do vídeo
    // ----------------------------------------------------------

    override suspend fun loadLinks(
        data: String,            // URL do episódio
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Busca todos os iframes (players de vídeo embutidos)
        val iframes = document.select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                iframe.attr("src").ifBlank { iframe.attr("data-src") }
            }
            .filter { it.isNotBlank() }

        // Se não achar iframe, tenta buscar links diretos de vídeo
        val directLinks = document.select(
            "source[src$='.mp4'], source[src$='.m3u8'], a[href$='.mp4']"
        ).mapNotNull { it.attr("src").ifBlank { it.attr("href") } }

        // Carrega cada iframe usando os extractores built-in do CloudStream
        // Eles sabem como extrair links de streamtape, mixdrop, etc.
        iframes.forEach { iframeUrl ->
            loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
        }

        // Links diretos .mp4 / .m3u8
        directLinks.forEach { videoUrl ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = fixUrl(videoUrl),
                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return iframes.isNotEmpty() || directLinks.isNotEmpty()
    }

    // ----------------------------------------------------------
    // UTILITÁRIO: codifica a query string para URL
    // ----------------------------------------------------------
    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
