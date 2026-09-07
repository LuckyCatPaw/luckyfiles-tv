package com.luckycatpaw.luckyfilestv.data.source.smb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.smbDataStore: DataStore<Preferences> by preferencesDataStore(name = "smb_shares")

/**
 * The shares the user configured.
 *
 * Stored as one JSON document rather than as loose keys: a share is only ever read and
 * written as a whole, and a list of records in a key-value store otherwise turns into index
 * arithmetic. Passwords are encrypted through [SmbSecretStore]; the preferences file itself
 * holds nothing readable.
 */
internal class SmbShareRepository(
    private val context: Context,
    private val secrets: SmbSecretStore = SmbSecretStore()
) : SmbShareStore {

    /**
     * Last decoded document, kept so reading the shares stays cheap.
     *
     * Every single call against a share resolves it through this list first, so copying a
     * folder used to parse the JSON and run one keystore decryption per file. The document
     * is the cache key, which means a change to the stored value invalidates it by itself
     * and no write has to remember to.
     *
     * The decrypted passwords live here for as long as the process does. That is no worse
     * than before — every [SmbShare] handed to the session pool already carries one — but
     * it is the reason this cache is not shared beyond the repository.
     */
    @Volatile
    private var decoded: DecodedShares? = null

    /**
     * A damaged preferences file arrives here as an IOException, and callers of this one sit
     * on scopes that other work shares. Answering with no shares is what an unreadable store
     * means; taking the scope down with it is not.
     */
    val shares: Flow<List<SmbShare>> = context.smbDataStore.data
        .catch { failure -> if (failure is IOException) emit(emptyPreferences()) else throw failure }
        .map { preferences -> preferences[SHARES]?.let(::decodeCached).orEmpty() }

    override suspend fun shares(): List<SmbShare> = shares.first()

    /** Adds a share, or replaces the one with the same [SmbShare.id]. */
    suspend fun save(share: SmbShare) {
        update { current ->
            val index = current.indexOfFirst { it.id == share.id }
            if (index < 0) current + share else current.toMutableList().apply { this[index] = share }
        }
    }

    suspend fun remove(id: String) {
        update { current -> current.filterNot { it.id == id } }
    }

    /**
     * Drops the pooled connections after the stored shares changed.
     *
     * The session key is host, share and user, so a corrected password does not change it
     * and the pooled session keeps serving with the old credentials until the server drops
     * it. A removed share is worse: its socket and session stay open until the process ends,
     * to a server the user just took out of the list.
     *
     * Called by whoever owns both this and the pool, since a repository that reaches for a
     * connection pool by itself is a repository that cannot be constructed without one.
     */
    suspend fun invalidateSessions(sessions: SmbSessionPool) {
        decoded = null
        sessions.closeAll()
    }

    private suspend fun update(transform: (List<SmbShare>) -> List<SmbShare>) {
        context.smbDataStore.edit { preferences ->
            val current = preferences[SHARES]?.let(::decodeCached).orEmpty()
            preferences[SHARES] = encode(transform(current))
        }
    }

    private fun decodeCached(stored: String): List<SmbShare> {
        decoded
            ?.takeIf { it.document == stored }
            ?.let { return it.shares }

        val shares = decode(stored)
        decoded = DecodedShares(document = stored, shares = shares)
        return shares
    }

    private fun encode(shares: List<SmbShare>): String {
        val array = JSONArray()

        shares.forEach { share ->
            array.put(
                JSONObject()
                    .put(ID, share.id)
                    .put(HOST, share.host)
                    .put(NAME, share.name)
                    .put(DISPLAY_NAME, share.displayName)
                    .put(CREDENTIALS, encode(share.credentials))
            )
        }

        return array.toString()
    }

    private fun encode(credentials: SmbCredentials): JSONObject = when (credentials) {
        SmbCredentials.Anonymous -> JSONObject().put(TYPE, ANONYMOUS)

        SmbCredentials.Guest -> JSONObject().put(TYPE, GUEST)

        is SmbCredentials.Password -> JSONObject()
            .put(TYPE, PASSWORD)
            .put(USER, credentials.user)
            .put(DOMAIN, credentials.domain.orEmpty())
            .put(SECRET, secrets.encrypt(credentials.password))

        // Written back exactly as it was read. Editing one share rewrites the whole
        // document, and a secret that is unreadable today must not be lost because of it.
        is SmbCredentials.Unreadable -> JSONObject()
            .put(TYPE, PASSWORD)
            .put(USER, credentials.user)
            .put(DOMAIN, credentials.domain.orEmpty())
            .put(SECRET, credentials.storedSecret)
    }

    /** A stored document that cannot be read must not take the share list with it. */
    private fun decode(stored: String): List<SmbShare> = runCatching {
        val array = JSONArray(stored)

        (0 until array.length()).mapNotNull { index ->
            runCatching { decode(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun decode(entry: JSONObject): SmbShare = SmbShare(
        id = entry.getString(ID),
        host = entry.getString(HOST),
        name = entry.getString(NAME),
        displayName = entry.getString(DISPLAY_NAME),
        credentials = decodeCredentials(entry.getJSONObject(CREDENTIALS))
    )

    private fun decodeCredentials(entry: JSONObject): SmbCredentials = when (entry.getString(TYPE)) {
        GUEST -> SmbCredentials.Guest
        PASSWORD -> decodePassword(entry)
        else -> SmbCredentials.Anonymous
    }

    /**
     * A secret that will not decrypt does not become an empty password.
     *
     * The share stays in the list and keeps its account name; what is missing is asked for
     * in the editor. Dropping the entry instead would make the share disappear without a
     * word, and an empty password would be a failed login against a real account.
     */
    private fun decodePassword(entry: JSONObject): SmbCredentials {
        val user = entry.getString(USER)
        val domain = entry.optString(DOMAIN).takeIf { it.isNotBlank() }
        val stored = entry.getString(SECRET)

        val password = secrets.decrypt(stored)
            ?: return SmbCredentials.Unreadable(user = user, domain = domain, storedSecret = stored)

        return SmbCredentials.Password(user = user, password = password, domain = domain)
    }

    private class DecodedShares(val document: String, val shares: List<SmbShare>)

    private companion object {
        val SHARES = stringPreferencesKey("shares")

        const val ID = "id"
        const val HOST = "host"
        const val NAME = "name"
        const val DISPLAY_NAME = "displayName"
        const val CREDENTIALS = "credentials"
        const val TYPE = "type"
        const val USER = "user"
        const val DOMAIN = "domain"
        const val SECRET = "secret"
        const val ANONYMOUS = "anonymous"
        const val GUEST = "guest"
        const val PASSWORD = "password"
    }
}
