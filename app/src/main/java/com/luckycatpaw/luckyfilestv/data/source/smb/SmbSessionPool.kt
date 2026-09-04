package com.luckycatpaw.luckyfilestv.data.source.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    suspend fun <T> withShare(share: SmbShare, block: (DiskShare) -> T): T = withContext(dispatcher) {
        val connected = acquire(share)

        try {
            block(connected.diskShare)
        } catch (dropped: TransportException) {
            evict(share)
            block(acquire(share).diskShare)
        }
    }

    /**
     * Throws every session away when the device changes network.
     *
     * A session survives in the pool long after the connection under it is gone: standby,
     * a switch between Wi-Fi and Ethernet or a VPN coming up all leave a socket that still
     * reports being connected. Without this the next access waits for the socket timeout
     * before it notices, which on a TV feels like the app has hung. Reconnecting costs one
     * handshake instead.
     */
    private fun watchNetwork(context: Context) {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                scope.launch { closeAll() }
            }

            override fun onLost(network: Network) {
                scope.launch { closeAll() }
            }
        }

        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
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

        // Anything that fails after the connection is open has to take it down with it:
        // authentication, a share that does not exist, a printer queue instead of a disk.
        // Otherwise a socket and a session stay behind on every failed attempt, and a wrong
        // password retried a few times leaves the server holding them.
        val connection = connect(share.host)
        var session: Session? = null

        try {
            val authenticated = connection.authenticate(share.credentials.toAuthenticationContext())
            session = authenticated

            val diskShare = authenticated.connectShare(share.name) as? DiskShare
                ?: throw SourceException.Unsupported("Not a file share: ${share.name}")

            PooledShare(connection, authenticated, diskShare).also { pooled[share.sessionKey] = it }
        } catch (failure: Throwable) {
            runCatching { session?.close() }
            runCatching { connection.close() }
            throw failure
        }
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

    companion object {

        @Volatile
        private var shared: SmbSessionPool? = null

        /**
         * One pool for the whole process.
         *
         * Browser, picker and the content provider all reach the same shares; separate pools
         * would mean separate connections and separate logins to the same server. The
         * connection test deliberately does not use this one — it has to try credentials
         * that are not stored yet.
         */
        fun shared(context: Context): SmbSessionPool = shared ?: synchronized(this) {
            shared ?: SmbSessionPool()
                .also { pool ->
                    pool.watchNetwork(context.applicationContext)
                    shared = pool
                }
        }

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
