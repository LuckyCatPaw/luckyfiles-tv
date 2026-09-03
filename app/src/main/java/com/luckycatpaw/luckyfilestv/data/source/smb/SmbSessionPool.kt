package com.luckycatpaw.luckyfilestv.data.source.smb

import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps one connected share per configuration.
 *
 * Connecting means a TCP handshake, a protocol negotiation and an authentication, which is
 * far too expensive to repeat for every directory the user steps into. Sessions do go away
 * on their own though — servers drop idle clients, and a TV loses its network when it sleeps
 * — so a dead session is not an error the user should see: the pool throws the session away
 * and tries once more on a fresh one.
 *
 * Only [TransportException] is retried. A protocol answer such as "access denied" is a real
 * result and repeating it would only cost another round trip.
 */
internal class SmbSessionPool(
    private val config: SmbConfig = defaultConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val client: SMBClient by lazy { SMBClient(config) }
    private val mutex = Mutex()
    private val pooled = mutableMapOf<String, PooledShare>()

    suspend fun <T> withShare(share: SmbShare, block: (DiskShare) -> T): T = withContext(dispatcher) {
        val connected = acquire(share)

        try {
            block(connected.diskShare)
        } catch (dropped: TransportException) {
            evict(share)
            block(acquire(share).diskShare)
        }
    }

    /** Drops every session, e.g. when the app stops or the shares were reconfigured. */
    suspend fun closeAll() {
        mutex.withLock {
            pooled.values.forEach { it.closeQuietly() }
            pooled.clear()
        }
    }

    private suspend fun acquire(share: SmbShare): PooledShare = mutex.withLock {
        pooled[share.sessionKey]
            ?.takeIf { it.diskShare.isConnected }
            ?.let { return@withLock it }

        pooled.remove(share.sessionKey)?.closeQuietly()

        val connection = connect(share.host)
        val session = connection.authenticate(share.credentials.toAuthenticationContext())
        val diskShare = session.connectShare(share.name) as? DiskShare
            ?: throw SourceException.Unsupported("Not a file share: ${share.name}")

        PooledShare(connection, session, diskShare).also { pooled[share.sessionKey] = it }
    }

    private suspend fun evict(share: SmbShare) {
        mutex.withLock { pooled.remove(share.sessionKey) }?.closeQuietly()
    }

    private fun connect(host: String): Connection {
        val separator = host.lastIndexOf(':')
        val port = if (separator > 0) host.substring(separator + 1).toIntOrNull() else null

        return if (port == null) client.connect(host) else client.connect(host.substring(0, separator), port)
    }

    private class PooledShare(
        private val connection: Connection,
        private val session: Session,
        val diskShare: DiskShare
    ) {

        fun closeQuietly() {
            runCatching { diskShare.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    private companion object {

        /**
         * A sleeping NAS must not freeze a screen, so the timeouts are short enough to fail
         * visibly and long enough to survive a slow spin-up.
         */
        fun defaultConfig(): SmbConfig = SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withDfsEnabled(true)
            .withSocketFactory(DirectSocketFactory())
            .build()
    }
}

private fun SmbCredentials.toAuthenticationContext(): AuthenticationContext = when (this) {
    SmbCredentials.Anonymous -> AuthenticationContext.anonymous()
    SmbCredentials.Guest -> AuthenticationContext.guest()
    is SmbCredentials.Password -> AuthenticationContext(user, password.toCharArray(), domain)
}
