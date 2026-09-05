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
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a call does to the server, which decides whether it may be sent twice.
 *
 * A [TransportException] says the connection broke, not whether the request reached the
 * server before it did. For a listing that distinction does not matter. For a rename it
 * decides between a second attempt that fails with "already exists" on the entry the first
 * one created, and a delete that reports "not found" for something it removed itself.
 */
internal enum class SmbCallKind {

    /** Reads only. Repeating it on a fresh session cannot change the outcome. */
    IDEMPOTENT,

    /**
     * Changes something on the server. A dropped transport is reported as unreachable so
     * the user retries deliberately, rather than the pool guessing on their behalf.
     */
    MUTATING
}

/**
 * Keeps one connected share per configuration.
 *
 * Connecting means a TCP handshake, a protocol negotiation and an authentication, which is
 * far too expensive to repeat for every directory the user steps into. Sessions do go away
 * on their own though — servers drop idle clients, and a TV loses its network when it sleeps
 * — so a dead session is not an error the user should see: the pool throws the session away
 * and tries once more on a fresh one.
 *
 * Only [TransportException] is retried, and only for an [SmbCallKind.IDEMPOTENT] call. A
 * protocol answer such as "access denied" is a real result and repeating it would only cost
 * another round trip.
 *
 * A session is never closed while someone is still reading from it. Every borrower holds a
 * lease, and dropping a session — because the network changed, because it turned out to be
 * dead — only marks it retired: it leaves the pool immediately, so nobody is handed it
 * again, and the socket goes down once the last lease comes back. A copy halfway through a
 * file therefore finishes instead of dying on a closed share.
 */
internal class SmbSessionPool(
    private val config: SmbConfig = defaultConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val clientHolder = lazy { SMBClient(config) }
    private val client: SMBClient by clientHolder
    private val mutex = Mutex()
    private val pooled = mutableMapOf<String, Connecting>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var networkWatch: NetworkWatch? = null

    suspend fun <T> withShare(
        share: SmbShare,
        kind: SmbCallKind,
        block: (DiskShare) -> T
    ): T = withContext(dispatcher) {
        try {
            useOnce(share, block)
        } catch (dropped: TransportException) {
            if (kind == SmbCallKind.MUTATING) throw dropped

            useOnce(share, block)
        }
    }

    /**
     * Borrows a share for longer than a single call.
     *
     * [withShare] hands its session back the moment the block returns, which is wrong for
     * everything that keeps something attached to the share: an output stream still being
     * written to, a file handle a player reads from. Those hold a lease instead and return
     * it when they are closed.
     */
    suspend fun lease(share: SmbShare): SmbShareLease = withContext(dispatcher) {
        SmbShareLease(acquire(share))
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
            .onSuccess { networkWatch = NetworkWatch(connectivityManager, callback) }
    }

    /**
     * Gives up the network callback and the connections behind it.
     *
     * The shared pool never calls this and does not need to: it is created once and lives as
     * long as the process, so its callback is a fixture rather than something that
     * accumulates. What was missing was any way to let go at all — a pool built for a
     * connection test or a test case had no way to stop watching, which is what this is for.
     */
    suspend fun dispose() {
        networkWatch?.let { watch ->
            networkWatch = null
            runCatching { watch.connectivityManager.unregisterNetworkCallback(watch.callback) }
        }

        closeAll()

        // Backstop, and the reason it runs before the scope goes down: a session still
        // shaking hands is retired by a coroutine on that scope, and cancelling it first
        // would leave the socket underneath open for good. Closing the client takes every
        // connection it ever made with it. Untouched when nobody ever connected, so a pool
        // built for a connection test does not open a client just to close it again.
        if (clientHolder.isInitialized()) runCatching { clientHolder.value.close() }

        scope.cancel()
    }

    private class NetworkWatch(
        val connectivityManager: ConnectivityManager,
        val callback: ConnectivityManager.NetworkCallback
    )

    /**
     * Drops every session, e.g. when the app stops or the shares were reconfigured.
     *
     * Returns as soon as the sessions are out of the pool. Ones that are still being read
     * from close themselves later, when their last lease is returned.
     */
    suspend fun closeAll() {
        val dropped = mutex.withLock {
            val current = pooled.values.toList()
            pooled.clear()
            current
        }

        dropped.forEach(::retire)
    }

    private suspend fun <T> useOnce(share: SmbShare, block: (DiskShare) -> T): T {
        val borrowed = acquire(share)

        try {
            return block(borrowed.diskShare)
        } catch (dropped: TransportException) {
            evict(share, borrowed)
            throw dropped
        } finally {
            borrowed.release()
        }
    }

    /**
     * A session with a lease already taken on it.
     *
     * Retried rather than looped: a session can be retired between being awaited and being
     * leased, but only by something that also took it out of the pool, so the next attempt
     * starts a fresh connection instead of finding the same corpse again.
     */
    private suspend fun acquire(share: SmbShare): PooledShare {
        repeat(ACQUIRE_ATTEMPTS) {
            val connecting = mutex.withLock { connectingLocked(share) }

            val opened = try {
                connecting.deferred.await()
            } catch (failure: Throwable) {
                forgetFailed(share.sessionKey, connecting)
                throw failure
            }

            if (opened.lease()) return opened
        }

        throw SourceException.Unreachable(share.host)
    }

    /**
     * The connection attempt for this share, started if there is none.
     *
     * Only the map access happens under the pool mutex. Holding it across the handshake
     * would let one sleeping server block every other share for the length of its timeout,
     * including [closeAll] and the eviction of unrelated sessions.
     */
    private fun connectingLocked(share: SmbShare): Connecting {
        pooled[share.sessionKey]
            ?.takeIf { !it.unusable }
            ?.let { return it }

        pooled.remove(share.sessionKey)?.let(::retire)

        return Connecting(share).also { pooled[share.sessionKey] = it }
    }

    /** Takes a failed attempt out of the pool so the next caller connects again. */
    private suspend fun forgetFailed(sessionKey: String, connecting: Connecting) {
        // A cancelled caller leaves the attempt itself alone: someone else may still be
        // waiting for exactly this connection.
        if (!connecting.deferred.isCompleted || connecting.opened != null) return

        withContext(NonCancellable) {
            mutex.withLock {
                if (pooled[sessionKey] === connecting) pooled.remove(sessionKey)
            }
        }
    }

    private suspend fun evict(share: SmbShare, dead: PooledShare) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (pooled[share.sessionKey]?.opened === dead) pooled.remove(share.sessionKey)
            }
        }

        dead.retire()
    }

    /** Marks a session for closing; the socket goes down once the last lease is returned. */
    private fun retire(connecting: Connecting) {
        val opened = connecting.opened

        if (opened != null) {
            opened.retire()
            return
        }

        // Still shaking hands. Waiting for that here would move the handshake timeout into
        // the caller of closeAll(), so the retirement happens once the session exists.
        scope.launch { runCatching { connecting.deferred.await() }.getOrNull()?.retire() }
    }

    private fun open(share: SmbShare): PooledShare {
        // Resolved before the socket, so an unreadable password costs no connection and,
        // more importantly, no failed login attempt against the account behind it.
        val credentials = share.credentials.toAuthenticationContext(share.host)

        // Anything that fails after the connection is open has to take it down with it:
        // authentication, a share that does not exist, a printer queue instead of a disk.
        // Otherwise a socket and a session stay behind on every failed attempt, and a wrong
        // password retried a few times leaves the server holding them.
        val connection = connect(share.host)
        var session: Session? = null

        try {
            val authenticated = connection.authenticate(credentials)
            session = authenticated

            val diskShare = authenticated.connectShare(share.name) as? DiskShare
                ?: throw SourceException.Unsupported("Not a file share: ${share.name}")

            return PooledShare(connection, authenticated, diskShare)
        } catch (failure: Throwable) {
            runCatching { session?.close() }
            runCatching { connection.close() }
            throw failure
        }
    }

    private fun connect(host: String): Connection {
        val endpoint = SmbEndpoint.parse(host)
        val port = endpoint.port

        return if (port == null) client.connect(endpoint.host) else client.connect(endpoint.host, port)
    }

    /** One connection attempt, shared by everyone asking for the same share while it runs. */
    private inner class Connecting(share: SmbShare) {

        @Volatile
        var opened: PooledShare? = null
            private set

        val deferred: Deferred<PooledShare> = scope.async { open(share).also { opened = it } }

        /**
         * `true` once this entry can no longer serve anyone: the handshake failed, or the
         * session behind it is retired or disconnected. An attempt still running is not
         * unusable — the next caller waits for it instead of opening a second connection.
         */
        val unusable: Boolean
            get() = deferred.isCompleted && opened?.usable != true
    }

    /**
     * A connected share plus the number of borrowers currently on it.
     *
     * The counter is guarded by a plain monitor rather than by the pool mutex: leasing and
     * returning happen on every single call, and neither may end up waiting behind a
     * handshake.
     */
    internal class PooledShare(
        private val connection: Connection,
        private val session: Session,
        val diskShare: DiskShare
    ) {

        private val lock = Any()
        private var users = 0
        private var retired = false
        private var closed = false

        val usable: Boolean
            get() = synchronized(lock) { !retired } && diskShare.isConnected

        /** `false` when the session was retired in the meantime and must not be handed out. */
        fun lease(): Boolean = synchronized(lock) {
            if (retired || !diskShare.isConnected) {
                false
            } else {
                users++
                true
            }
        }

        fun release() {
            val last = synchronized(lock) {
                // Floored rather than decremented blindly. A borrower that returns twice
                // would otherwise drive the count below zero, at which point the session
                // reads as idle while somebody is still reading from it.
                if (users > 0) users--

                retired && users <= 0
            }

            if (last) closeNow()
        }

        /** Closes as soon as nobody is reading any more. */
        fun retire() {
            val idle = synchronized(lock) {
                if (retired) return
                retired = true
                users <= 0
            }

            if (idle) closeNow()
        }

        /** Runs once. Retiring an idle session and returning its last lease can both land here. */
        private fun closeNow() {
            synchronized(lock) {
                if (closed) return
                closed = true
            }

            runCatching { diskShare.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
        }
    }

    companion object {

        /** A retired session costs one more attempt, not an unbounded loop. */
        private const val ACQUIRE_ATTEMPTS = 3

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

/**
 * A share borrowed from the pool for as long as the holder needs it.
 *
 * Closing returns it. Never closing keeps a session and its socket alive for the rest of the
 * process, so a lease belongs in a `use` block or in the `close()` of whatever outlives the
 * call that took it.
 */
internal class SmbShareLease internal constructor(
    private val borrowed: SmbSessionPool.PooledShare
) : Closeable {

    private val returned = AtomicBoolean(false)

    val diskShare: DiskShare
        get() = borrowed.diskShare

    override fun close() {
        if (returned.compareAndSet(false, true)) borrowed.release()
    }
}

private fun SmbCredentials.toAuthenticationContext(host: String): AuthenticationContext = when (this) {
    SmbCredentials.Anonymous -> AuthenticationContext.anonymous()
    SmbCredentials.Guest -> AuthenticationContext.guest()
    is SmbCredentials.Password -> AuthenticationContext(user, password.toCharArray(), domain)
    is SmbCredentials.Unreadable -> throw SourceException.AuthenticationRequired(host)
}
