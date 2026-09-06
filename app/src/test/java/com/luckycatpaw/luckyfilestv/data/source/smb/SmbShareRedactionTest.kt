package com.luckycatpaw.luckyfilestv.data.source.smb

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The promise that a share can be printed.
 *
 * A data class generates a toString that prints every property, and one of these carries a
 * password. It takes one log line, one exception message built from a share or one crash
 * report for the plaintext to leave the process, which would undo encrypting it at rest.
 * The overrides say so; these tests are what keeps them true when a field is added.
 */
class SmbShareRedactionTest {

    @Test
    fun `printing a share does not print the password`() {
        val printed = share(SmbCredentials.Password(user = "vogt", password = SECRET)).toString()

        assertFalse(printed.contains(SECRET))
        assertTrue(printed.contains("nas"))
        assertTrue(printed.contains("vogt"))
    }

    @Test
    fun `printing the credentials on their own does not print the password either`() {
        // A share is not the only thing that ends up in a message; the credentials travel
        // on their own through the session pool.
        val printed = SmbCredentials.Password(user = "vogt", password = SECRET, domain = "WORKGROUP").toString()

        assertFalse(printed.contains(SECRET))
        assertTrue(printed.contains("vogt"))
    }

    @Test
    fun `printing an unreadable credential does not print the stored ciphertext`() {
        val credentials = SmbCredentials.Unreadable(user = "vogt", domain = null, storedSecret = SECRET)

        val printed = share(credentials).toString() + credentials.toString()

        assertFalse(printed.contains(SECRET))
    }

    @Test
    fun `redacting is only about printing, the value itself is kept`() {
        // Equality and copy still see the password; only toString does not.
        val credentials = SmbCredentials.Password(user = "vogt", password = SECRET)

        assertEquals(SECRET, credentials.password)
        assertEquals(SECRET, credentials.copy(user = "other").password)
        assertEquals(credentials, SmbCredentials.Password(user = "vogt", password = SECRET))
    }

    @Test
    fun `identity names the account without naming its secret`() {
        assertEquals("vogt", SmbCredentials.Password(user = "vogt", password = SECRET).identity)
        assertEquals(
            "WORKGROUP\\vogt",
            SmbCredentials.Password(user = "vogt", password = SECRET, domain = "WORKGROUP").identity
        )
        // A blank domain is the same as none, not an empty prefix.
        assertEquals("vogt", SmbCredentials.Password(user = "vogt", password = SECRET, domain = "  ").identity)
        assertEquals("anonymous", SmbCredentials.Anonymous.identity)
        assertEquals("guest", SmbCredentials.Guest.identity)
    }

    @Test
    fun `two accounts on the same share are two sessions`() {
        // The session key decides what gets pooled, so it has to separate the users without
        // carrying their secrets into a map key.
        val first = share(SmbCredentials.Password(user = "vogt", password = SECRET)).sessionKey
        val second = share(SmbCredentials.Password(user = "other", password = SECRET)).sessionKey

        assertFalse(first == second)
        assertFalse(first.contains(SECRET))
    }

    @Test
    fun `the path of a share is its root on the server`() {
        assertEquals("smb://nas/media", share(SmbCredentials.Guest).path.value)
    }

    private fun share(credentials: SmbCredentials): SmbShare = SmbShare(
        host = "nas",
        name = "media",
        displayName = "Media",
        credentials = credentials
    )

    private companion object {
        const val SECRET = "hunter2-correct-horse"
    }
}
