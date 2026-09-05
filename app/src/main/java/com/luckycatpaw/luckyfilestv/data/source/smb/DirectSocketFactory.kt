package com.luckycatpaw.luckyfilestv.data.source.smb

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.net.SocketFactory

/**
 * Sockets that never go through a proxy.
 *
 * Two layers have to be switched off for that. smbj asks the platform `ProxySelector`
 * through its own factory, and below that every plain [Socket] does the same again on its
 * own — so leaving out the first one is not enough. Only a socket built with
 * [Proxy.NO_PROXY] skips both.
 *
 * A share lives on the local network. Routing it through a proxy is never right, and when
 * the configured proxy is not listening the connection is refused before a single SMB byte
 * is written.
 *
 * Because this factory hands smbj an already connected socket, smbj never gets to apply its
 * own timeout to the connect — the timeout configured on `SmbConfig` covers the requests
 * that follow, not the handshake below them. Without [connectTimeoutMillis] the connect falls
 * back to the platform default, which is the kernel giving up on its SYN retries after
 * roughly two minutes. A sleeping NAS then blocks whoever asked for the socket for that
 * long, and the caller is not always a coroutine that can be cancelled: reading a file on a
 * share goes through a content provider on a Binder thread, so the wait lands in the app
 * that asked us for the file and shows up there as an ANR.
 */
internal class DirectSocketFactory(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS
) : SocketFactory() {

    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port), null)

    override fun createSocket(host: String, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        connected(InetSocketAddress(host, port), InetSocketAddress(localAddress, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port), null)

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = connected(InetSocketAddress(host, port), InetSocketAddress(localAddress, localPort))

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress?): Socket {
        val socket = Socket(Proxy.NO_PROXY)

        try {
            if (local != null) socket.bind(local)

            // Zero is the platform's "wait forever", so a timeout configured away is not
            // silently turned back into the default this exists to avoid.
            socket.connect(remote, connectTimeoutMillis.coerceAtLeast(1))
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }

        return socket
    }

    companion object {

        /**
         * Matches the transaction timeout the session pool configures. A server that cannot
         * complete a TCP handshake in this long is not one the next request would reach
         * either, and failing here at least fails with a message instead of a frozen screen.
         */
        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000
    }
}
