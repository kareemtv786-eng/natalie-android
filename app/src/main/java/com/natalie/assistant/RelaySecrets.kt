package com.natalie.assistant

/**
 * M2.2 relay credentials — GENERATED AT BUILD TIME, DO NOT COMMIT.
 *
 * This is the PHONE role token (can send messages + read replies only).
 * It is NOT the privileged NATALIE server token. The PWA stores this same
 * token in the phone's localStorage; here it ships in the test APK.
 * For a production build, replace this with a user-entered setup screen
 * storing the token in EncryptedSharedPreferences.
 */
object RelaySecrets {
    const val PHONE_TOKEN = "9f67ae2704425fdf2f69649c62b21d8e0b4475694413a6d851c52a1a79703ff7"
}
