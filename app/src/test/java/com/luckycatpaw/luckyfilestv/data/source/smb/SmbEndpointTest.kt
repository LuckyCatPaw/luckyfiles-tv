package com.luckycatpaw.luckyfilestv.data.source.smb

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class SmbEndpointTest {

    @Test
    fun `a bare host has no port`() {
        val endpoint = SmbEndpoint.parse("nas")

        assertEquals("nas", endpoint.host)
        assertNull(endpoint.port)
    }

    @Test
    fun `a host and a port split on the single colon`() {
        assertEquals(SmbEndpoint("nas", 445), SmbEndpoint.parse("nas:445"))
        assertEquals(SmbEndpoint("192.168.1.10", 139), SmbEndpoint.parse("192.168.1.10:139"))
    }

    @Test
    fun `a bare IPv6 literal stays whole`() {
        // Splitting on the last colon would connect to `fe80:` on port 1.
        assertEquals(SmbEndpoint("fe80::1", null), SmbEndpoint.parse("fe80::1"))
        assertEquals(SmbEndpoint("::1", null), SmbEndpoint.parse("::1"))
        assertEquals(
            SmbEndpoint("2001:db8:85a3::8a2e:370:7334", null),
            SmbEndpoint.parse("2001:db8:85a3::8a2e:370:7334")
        )
    }

    @Test
    fun `brackets are what separates an IPv6 address from its port`() {
        assertEquals(SmbEndpoint("fe80::1", 445), SmbEndpoint.parse("[fe80::1]:445"))
        assertEquals(SmbEndpoint("fe80::1", null), SmbEndpoint.parse("[fe80::1]"))
    }

    @Test
    fun `surrounding space is not part of the address`() {
        assertEquals(SmbEndpoint("nas", 445), SmbEndpoint.parse("  nas:445  "))
        assertEquals(SmbEndpoint("fe80::1", 445), SmbEndpoint.parse("\t[fe80::1]:445\n"))
    }

    @Test
    fun `a port outside the range leaves the whole string as the address`() {
        // Name resolution gets to produce the error rather than the parser guessing.
        assertEquals(SmbEndpoint("nas:0", null), SmbEndpoint.parse("nas:0"))
        assertEquals(SmbEndpoint("nas:70000", null), SmbEndpoint.parse("nas:70000"))
        assertEquals(SmbEndpoint("nas:-1", null), SmbEndpoint.parse("nas:-1"))
        assertEquals(SmbEndpoint("nas:abc", null), SmbEndpoint.parse("nas:abc"))
        assertEquals(SmbEndpoint("nas:", null), SmbEndpoint.parse("nas:"))
    }

    @Test
    fun `the range ends where the protocol says it does`() {
        assertEquals(1, SmbEndpoint.parse("nas:1").port)
        assertEquals(65_535, SmbEndpoint.parse("nas:65535").port)
        assertNull(SmbEndpoint.parse("nas:65536").port)
    }

    // Records the asymmetry rather than endorsing it: with brackets the address is already
    // delimited, so a bad port is simply dropped. Without them the colon might belong to
    // the address, so the string is kept as it was typed.
    @Test
    fun `a bad port behind brackets is dropped and the address survives`() {
        assertEquals(SmbEndpoint("fe80::1", null), SmbEndpoint.parse("[fe80::1]:0"))
        assertEquals(SmbEndpoint("fe80::1", null), SmbEndpoint.parse("[fe80::1]junk"))
    }

    @Test
    fun `an unclosed or empty bracket falls back to the plain reading`() {
        assertEquals(SmbEndpoint("[]", null), SmbEndpoint.parse("[]"))
        assertEquals(SmbEndpoint("[fe80::1", null), SmbEndpoint.parse("[fe80::1"))
    }

    @Test
    fun `nothing typed is an empty address rather than a failure`() {
        // The share editor validates before this; the parser only has to not invent a port.
        assertEquals(SmbEndpoint("", null), SmbEndpoint.parse(""))
        assertEquals(SmbEndpoint("", null), SmbEndpoint.parse("   "))
    }

    @Test
    fun `a leading colon is not a port`() {
        assertEquals(SmbEndpoint(":445", null), SmbEndpoint.parse(":445"))
    }
}
