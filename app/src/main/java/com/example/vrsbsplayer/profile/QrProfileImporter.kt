package com.example.vrsbsplayer.profile

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reproduces the decoding behaviour documented for googlevr/cardboard's
 * QrCodeContentProcessor:
 *
 *   "If the decoded URL matches https://google.com/cardboard or
 *    https://google.com/cardboard/cfg?p=..., it is parsed to retrieve the
 *    Cardboard viewer device parameters. Otherwise, the decoded URL is
 *    requested via a normal web request. Any HTTP redirects are followed
 *    until a matching URL is found."
 *
 * We do not read the official Cardboard app's private storage (it's sandboxed
 * and inaccessible without root, which this app deliberately avoids). Instead
 * we scan the QR code ourselves and apply the same URL-matching rule.
 */
object QrProfileImporter {

    // Matches https://google.com/cardboard and https://google.com/cardboard/cfg?p=...
    // The payload is url-safe base64, which may also carry "=" padding.
    private val CARDBOARD_URL_REGEX = Regex(
        "^https?://(www\\.)?(vr\\.)?google\\.com/cardboard(/cfg)?(/download)?" +
            "(/?\\?p=(?<payload>[A-Za-z0-9_\\-=]+))?/?$"
    )

    sealed class Result {
        data class Success(val profile: ViewerProfile) : Result()
        data class Failure(val reason: String) : Result()
    }

    /** [rawContent] is whatever string CameraX/ML Kit decoded from the QR code. */
    fun importFrom(rawContent: String, newId: () -> String): Result {
        val direct = tryParseCardboardUrl(rawContent, newId)
        if (direct != null) return direct

        // Not a direct match: some older QR codes point at a short-link that
        // redirects to the real google.com/cardboard/cfg?p=... URL. Follow
        // redirects, exactly like the official SDK does, then retry parsing.
        return try {
            var currentUrl = rawContent
            repeat(5) {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return@repeat
                    conn.disconnect()
                    val matched = tryParseCardboardUrl(location, newId)
                    if (matched != null) return matched
                    currentUrl = location
                } else {
                    conn.disconnect()
                    return Result.Failure("QR code did not resolve to a Cardboard viewer profile URL.")
                }
            }
            Result.Failure("Too many redirects while resolving QR code URL.")
        } catch (e: Exception) {
            Result.Failure("Could not read QR code as a Cardboard profile: ${e.message}")
        }
    }

    private fun tryParseCardboardUrl(url: String, newId: () -> String): Result? {
        val match = CARDBOARD_URL_REGEX.find(url.trim()) ?: return null
        val payload = match.groups["payload"]?.value

        if (payload == null) {
            // Bare "https://google.com/cardboard" -> official Cardboard V1 defaults.
            return Result.Success(cardboardV1DefaultProfile(newId()))
        }

        return try {
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val params = CardboardProto.decode(bytes)
                ?: return Result.Failure("QR payload was not a valid DeviceParams message.")
            Result.Success(params.toViewerProfile(newId(), "Imported viewer"))
        } catch (e: Exception) {
            Result.Failure("Failed to decode QR payload: ${e.message}")
        }
    }
}
