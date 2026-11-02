package io.github.aedev.flow.sync.transport

import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Test

class BoundedListenerTest {
    private val socket = mockk<WebSocket>(relaxed = true)
    private val delegate = mockk<WebSocketListener>(relaxed = true)
    private val listener = BoundedListener(4, delegate)

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `messages within the limit reach the listener`() {
        listener.onMessage(socket, ByteArray(4).toByteString())
        listener.onMessage(socket, "abcd")
        verify(exactly = 1) { delegate.onMessage(socket, ByteArray(4).toByteString()) }
        verify(exactly = 1) { delegate.onMessage(socket, "abcd") }
        verify(exactly = 0) { socket.cancel() }
    }

    @Test
    fun `a message past the limit fails the socket and never reaches the listener`() {
        listener.onMessage(socket, ByteArray(5).toByteString())
        listener.onMessage(socket, "abcé")
        verify(exactly = 2) { socket.cancel() }
        verify(exactly = 0) { delegate.onMessage(socket, any<okio.ByteString>()) }
        verify(exactly = 0) { delegate.onMessage(socket, any<String>()) }
    }
}
