package com.luckycatpaw.luckyfilestv.data.source.smb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    val shares: Flow<List<SmbShare>> = context.smbDataStore.data.map { preferences ->
        preferences[SHARES]?.let(::decode).orEmpty().ifEmpty { ConfiguredSmbShares.compiledIn }
    }

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

    private suspend fun update(transform: (List<SmbShare>) -> List<SmbShare>) {
        context.smbDataStore.edit { preferences ->
            val current = preferences[SHARES]?.let(::decode).orEmpty()
            preferences[SHARES] = encode(transform(current))
        }
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
        PASSWORD -> SmbCredentials.Password(
            user = entry.getString(USER),
            password = secrets.decrypt(entry.getString(SECRET)).orEmpty(),
            domain = entry.optString(DOMAIN).takeIf { it.isNotBlank() }
        )

        else -> SmbCredentials.Anonymous
    }

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
