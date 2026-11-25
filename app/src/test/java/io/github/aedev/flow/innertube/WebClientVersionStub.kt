package io.github.aedev.flow.innertube

/** Stands in for the `sw.js` reads behind the web client versions, so no test touches the network. */
internal object WebClientVersionStub {
    private val original = YouTube.webClients

    fun answer(version: String) = install { """self.ytcfg={"INNERTUBE_CONTEXT_CLIENT_VERSION":"$version"}""" }

    fun fail() = install { throw java.io.IOException("no network") }

    fun reset() {
        YouTube.webClients = original
    }

    private fun install(fetch: suspend () -> String) {
        YouTube.webClients = WebClients { _, _ -> fetch() }
    }
}
