package org.multipaz.identityreader

/**
 * Whether this build talks to an Identity Reader backend (reader key certification, the
 * built-in trusted issuer list and Google sign-in). Off unless IDENTITY_READER_BACKEND_URL
 * is set at build time.
 */
val isReaderBackendEnabled: Boolean
    get() = BuildConfig.IDENTITY_READER_BACKEND_URL.isNotEmpty()
