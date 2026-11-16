package io.github.aedev.flow.data.translation

import com.google.common.truth.Truth.assertThat
import okio.Buffer
import org.junit.Test

class TranslationServerTest {
    private val libre = TranslationServer(TranslationServerType.LIBRETRANSLATE, "https://tr.example.org/", "secret")
    private val lingva = TranslationServer(TranslationServerType.LINGVA, "https://lingva.example.org/", null)

    @Test
    fun `https addresses are taken and get a trailing slash`() {
        assertThat(TranslationServer.normalizeUrl("https://tr.example.org")).isEqualTo("https://tr.example.org/")
        assertThat(TranslationServer.normalizeUrl(" https://example.org/lingva ")).isEqualTo("https://example.org/lingva/")
    }

    @Test
    fun `an address without a scheme is taken as https`() {
        assertThat(TranslationServer.normalizeUrl("tr.example.org")).isEqualTo("https://tr.example.org/")
    }

    @Test
    fun `plain http only reaches this device or the local network`() {
        assertThat(TranslationServer.normalizeUrl("http://tr.example.org")).isNull()
        assertThat(TranslationServer.normalizeUrl("http://192.168.1.20:5000")).isEqualTo("http://192.168.1.20:5000/")
        assertThat(TranslationServer.normalizeUrl("http://10.0.0.2:5000")).isNotNull()
        assertThat(TranslationServer.normalizeUrl("http://172.20.0.5")).isNotNull()
        assertThat(TranslationServer.normalizeUrl("http://172.32.0.5")).isNull()
        assertThat(TranslationServer.normalizeUrl("http://localhost:5000")).isNotNull()
        assertThat(TranslationServer.normalizeUrl("http://nas.local:5000")).isNotNull()
        assertThat(TranslationServer.normalizeUrl("http://nas:5000")).isNotNull()
        assertThat(TranslationServer.normalizeUrl("http://[::1]:5000")).isNotNull()
        assertThat(TranslationServer.normalizeUrl("http://8.8.8.8")).isNull()
    }

    @Test
    fun `queries, fragments, credentials and junk are refused`() {
        assertThat(TranslationServer.normalizeUrl("")).isNull()
        assertThat(TranslationServer.normalizeUrl("https://tr.example.org/?key=1")).isNull()
        assertThat(TranslationServer.normalizeUrl("https://tr.example.org/#x")).isNull()
        assertThat(TranslationServer.normalizeUrl("https://user:pw@tr.example.org/")).isNull()
        assertThat(TranslationServer.normalizeUrl("ftp://tr.example.org/")).isNull()
        assertThat(TranslationServer.normalizeUrl("not a url")).isNull()
    }

    @Test
    fun `LibreTranslate gets one POST with the text, target and key`() {
        val requests = libre.requests("Bonjour", "en-GB")
        assertThat(requests).hasSize(1)
        val request = requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.toString()).isEqualTo("https://tr.example.org/translate")
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertThat(body).isEqualTo("""{"q":"Bonjour","source":"auto","target":"en","format":"text","api_key":"secret"}""")
    }

    @Test
    fun `LibreTranslate without a key sends none`() {
        val request = libre.copy(apiKey = " ").requests("Hola", "en").single()
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertThat(body).doesNotContain("api_key")
    }

    @Test
    fun `Lingva takes the text in the path, encoded`() {
        val request = lingva.requests("¿Qué tal? 50/50", "en").single()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/auto/en/%C2%BFQu%C3%A9%20tal%3F%2050%2F50")
    }

    @Test
    fun `a long text goes to Lingva in pieces of whole lines that join back`() {
        val line = "日本語の文章です。".repeat(40)
        val text = List(6) { line }.joinToString("\n")
        val chunks = TranslationServer.lingvaChunks(text)
        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach { assertThat(it.toByteArray().size).isAtMost(TranslationServer.LINGVA_CHUNK_BYTES) }
        assertThat(chunks.joinToString("\n")).isEqualTo(text)
        assertThat(lingva.requests(text, "en")).hasSize(chunks.size)
    }

    @Test
    fun `responses are read for each server, and an error body is no translation`() {
        assertThat(libre.parse("""{"translatedText":"Hello","detectedLanguage":{"confidence":90,"language":"fr"}}""")).isEqualTo("Hello")
        assertThat(lingva.parse("""{"translation":"Hello","info":{"detectedSource":"fr"}}""")).isEqualTo("Hello")
        assertThat(libre.parse("""{"error":"Invalid API key"}""")).isNull()
        assertThat(lingva.parse("<html>Just a moment...</html>")).isNull()
    }

    @Test
    fun `language tags become each server's codes`() {
        val libreType = TranslationServerType.LIBRETRANSLATE
        val lingvaType = TranslationServerType.LINGVA
        assertThat(TranslationServer.targetCode("es-419", libreType)).isEqualTo("es")
        assertThat(TranslationServer.targetCode("zh-CN", lingvaType)).isEqualTo("zh")
        assertThat(TranslationServer.targetCode("zh-TW", libreType)).isEqualTo("zt")
        assertThat(TranslationServer.targetCode("zh-HK", lingvaType)).isEqualTo("zh_HANT")
        assertThat(TranslationServer.targetCode("iw", libreType)).isEqualTo("he")
        assertThat(TranslationServer.targetCode("he", lingvaType)).isEqualTo("iw")
        assertThat(TranslationServer.targetCode("fil", libreType)).isEqualTo("tl")
        assertThat(TranslationServer.targetCode("no", libreType)).isEqualTo("nb")
    }

    @Test
    fun `stored settings give a server only when Translate uses one and the address is valid`() {
        val stored = TranslationSettings(useServer = true, serverUrl = "https://tr.example.org/", apiKey = "k")
        assertThat(stored.server).isEqualTo(TranslationServer(TranslationServerType.LIBRETRANSLATE, "https://tr.example.org/", "k"))
        assertThat(stored.copy(useServer = false).server).isNull()
        assertThat(stored.copy(serverUrl = "http://tr.example.org/").server).isNull()
        assertThat(TranslationSettings(useServer = true).server).isNull()
    }

    @Test
    fun `the cache keeps translations per server, language and text`() {
        val cache = TranslationCache()
        val key = TranslationCache.key(libre, "en", "Bonjour")
        cache.put(key, Translation("Hello", libre.host))
        assertThat(cache[key]).isEqualTo(Translation("Hello", "tr.example.org"))
        assertThat(cache[TranslationCache.key(libre, "de", "Bonjour")]).isNull()
        assertThat(cache[TranslationCache.key(lingva, "en", "Bonjour")]).isNull()
    }

    @Test
    fun `a title and description go in one text and come back apart`() {
        val sent = titledText("Titre", "Ligne un\nLigne deux")
        assertThat(sent).isEqualTo("Titre\n\nLigne un\nLigne deux")
        val back = Translation("Title\n\nLine one\nLine two", "tr.example.org").splitTitle()
        assertThat(back).isEqualTo(TitledTranslation("Title", "Line one\nLine two", "tr.example.org"))
        assertThat(titledText("Titre", " ")).isEqualTo("Titre")
        assertThat(Translation("Title", "h").splitTitle().body).isEmpty()
    }
}
