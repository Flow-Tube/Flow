package io.github.aedev.flow.innertube

/** Stands in for the `sw.js` read behind YouTube's WEB client version, so no test touches the network. */
internal object WebClientVersionStub {
    private val original = YouTube.webClientVersion

    fun answer(version: String) = install { """self.ytcfg={"INNERTUBE_CONTEXT_CLIENT_VERSION":"$version"}""" }

    fun fail() = install { throw java.io.IOException("no network") }

    fun reset() {
        YouTube.webClientVersion = original
    }

    private fun install(fetch: suspend () -> String) {
        YouTube.webClientVersion = WebClientVersion(fetch)
    }
}
