package com.luckycatpaw.luckyfilestv.data.source.smb

/**
 * The server address of a share, split into what a socket needs.
 *
 * A configured host is a single string the user typed, and four shapes have to come out of
 * it correctly: `nas`, `nas:445`, `fe80::1` and `[fe80::1]:445`. Splitting on the last colon
 * handles the first two and quietly destroys the others — a bare IPv6 literal would connect
 * to `fe80:` on port 1.
 *
 * The rule is the one from RFC 3986: a port only exists after the brackets, or after the one
 * and only colon in the string. Everything else is part of the address.
 */
internal data class SmbEndpoint(val host: String, val port: Int?) {

    companion object {

        private const val SEPARATOR = ':'
        private const val OPENING_BRACKET = '['
        private const val CLOSING_BRACKET = ']'
        private val PORT_RANGE = 1..65_535

        fun parse(value: String): SmbEndpoint {
            val trimmed = value.trim()

            if (trimmed.startsWith(OPENING_BRACKET)) {
                val closing = trimmed.lastIndexOf(CLOSING_BRACKET)

                if (closing > 1) {
                    return SmbEndpoint(
                        host = trimmed.substring(1, closing),
                        port = portOrNull(trimmed.substring(closing + 1))
                    )
                }
            }

            val separator = trimmed.lastIndexOf(SEPARATOR)

            // Several colons without brackets: an IPv6 address, which leaves no room for a
            // port. One colon: a host and a port, the everyday case.
            if (separator <= 0 || trimmed.indexOf(SEPARATOR) != separator) {
                return SmbEndpoint(trimmed, null)
            }

            val port = portOrNull(trimmed.substring(separator))
                ?: return SmbEndpoint(trimmed, null)

            return SmbEndpoint(trimmed.substring(0, separator), port)
        }

        /**
         * @param suffix everything behind the address, so either empty or `:445`. Anything
         *   that is not a usable port number counts as absent, which leaves the string as
         *   the address and lets name resolution produce the error.
         */
        private fun portOrNull(suffix: String): Int? = suffix
            .takeIf { it.startsWith(SEPARATOR) }
            ?.substring(1)
            ?.toIntOrNull()
            ?.takeIf { it in PORT_RANGE }
    }
}
