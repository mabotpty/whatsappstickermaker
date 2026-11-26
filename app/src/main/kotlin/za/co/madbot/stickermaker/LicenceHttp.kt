package za.co.madbot.stickermaker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the licence server (madbotcrm, POST sticker/claim). The server verifies the Google ID token and the Play purchase itself. */
class HttpBackend(private val baseUrl: String) : LicenceBackend {
    override suspend fun claim(idToken: String, purchaseToken: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("id_token", idToken).put("purchase_token", purchaseToken).toString()
        val c = URL(baseUrl.trimEnd('/') + "/sticker/claim").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            parseClaim(status, text)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** Public so the answers the server can give are unit-tested. */
        fun parseClaim(status: Int, text: String): String {
            val json = try { JSONObject(text) } catch (e: Exception) { null }
            if (status in 200..299) {
                val key = json?.optString("key").orEmpty()
                if (key.isEmpty()) throw LicenceException("bad_response", "The licence server sent an answer this app does not understand.")
                return key
            }
            val code = json?.optString("error").orEmpty().ifEmpty { "http_$status" }
            val message = json?.optString("message").orEmpty().ifEmpty {
                if (status >= 500) "The licence server is not available right now. Try again later." else "The licence server refused the request ($status)."
            }
            throw LicenceException(code, message)
        }
    }
}
