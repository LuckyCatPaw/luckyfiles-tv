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
 */
internal class DirectSocketFactory : SocketFactory() {

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
            socket.connect(remote)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }

        return socket
    }
}
