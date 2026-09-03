package com.luckycatpaw.luckyfilestv.data.source.smb

import com.luckycatpaw.luckyfilestv.data.source.SourcePath

/**
 * A share the user configured.
 *
 * This is the persisted description of a share, not a connection: it exists while the server
 * is asleep and while the network is down. Everything that only becomes known during the
 * handshake — the negotiated dialect, encryption, whether the server is reachable at all —
 * deliberately lives in the session layer and not here.
 */
internal data class SmbShare(
    val host: String,
    val name: String,
    val displayName: String,
    val credentials: SmbCredentials
) {

    /** Location of the share root, e.g. `smb://nas/media`. */
    val path: SourcePath = SourcePath.remote(SCHEME, host, name)

    /** Identity of a pooled session. Credentials are part of it: two users are two sessions. */
    val sessionKey: String = "$host/$name/${credentials.identity}"

    companion object {
        const val SCHEME = "smb"
    }
}

internal sealed interface SmbCredentials {

    /** Value that distinguishes sessions without putting a secret into a map key. */
    val identity: String

    data object Anonymous : SmbCredentials {
        override val identity: String get() = "anonymous"
    }

    data object Guest : SmbCredentials {
        override val identity: String get() = "guest"
    }

    /**
     * Password stays a [String] for now because it comes from a hard coded test share. Once
     * the configuration UI lands this becomes a `CharArray` that can be cleared, held by an
     * encrypted store rather than by the model.
     */
    data class Password(val user: String, val password: String, val domain: String? = null) : SmbCredentials {
        override val identity: String get() = if (domain.isNullOrBlank()) user else "$domain\\$user"
    }
}

/** Where the configured shares come from. */
internal fun interface SmbShareStore {

    suspend fun shares(): List<SmbShare>
}

/**
 * The shares of a build.
 *
 * Empty until the configuration UI exists. To try a server out, add an entry here in a debug
 * build — the source itself needs no change for that, which is the point of testing the
 * protocol before building a screen around it.
 */
internal object ConfiguredSmbShares : SmbShareStore {

    private val configured: List<SmbShare> = emptyList()

    override suspend fun shares(): List<SmbShare> = configured
}
