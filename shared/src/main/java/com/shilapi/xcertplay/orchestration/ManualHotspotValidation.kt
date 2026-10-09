package com.shilapi.xcertplay.orchestration

/** Rules an existing (car) hotspot must meet before CarPlay can hand its credentials to the iPhone. */
object ManualHotspotValidation {
    /** Default when no security mode was selected; a password alone cannot identify WPA3. */
    fun securityFor(passphrase: String): ManualHotspotSecurity =
        if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2

    enum class Error(val message: String) {
        EMPTY_NAME("Enter the car hotspot name"),
        LONG_NAME("The hotspot name must be at most 32 bytes"),
        INVALID_CHARACTER("The name or password contains an invalid character"),
        PASSWORD_LENGTH("The hotspot password must be 8–63 characters"),
    }

    fun error(ssid: String, passphrase: String): Error? = when {
        ssid.isBlank() -> Error.EMPTY_NAME
        ssid.encodeToByteArray().size > 32 -> Error.LONG_NAME
        '\u0000' in ssid || '\u0000' in passphrase -> Error.INVALID_CHARACTER
        passphrase.isNotEmpty() && passphrase.length !in 8..63 -> Error.PASSWORD_LENGTH
        else -> null
    }

    /** English diagnostic text retained for non-UI callers. */
    fun validate(ssid: String, passphrase: String): String? = error(ssid, passphrase)?.message
}
