package ma.benaly.miniserre

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Live(val temperature: Double?, val humidite: Double?, val sol: Double?, val luminosite: Double?,
                val fan: Boolean?, val pump: Boolean?, val led: Boolean?, val mode: String?)
data class Control(val mode: String?, val fan: Boolean, val pump: Boolean, val led: Boolean)
data class Point(val ts: Long, val temperature: Double?, val humidite: Double?, val sol: Double?, val luminosite: Double?)

/** Firebase Realtime Database REST client; same data layout as the original dashboard. */
class FirebaseApi(private val origin: String) {
    private suspend fun call(path: String, method: String = "GET", body: String? = null): String =
        withContext(Dispatchers.IO) {
            val c = URL("$origin/serre$path").openConnection() as HttpURLConnection
            try {
                c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 8000
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                if (c.responseCode !in 200..299) throw IOException("Firebase HTTP ${c.responseCode}")
                c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }

    private fun JSONObject.d(k: String) = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null
    private fun JSONObject.b(k: String) = if (has(k) && !isNull(k)) optBoolean(k) else null

    suspend fun live(): Live? = call("/live.json").let { if (it == "null") null else JSONObject(it) }?.let {
        Live(it.d("temperature"), it.d("humidite"), it.d("sol"), it.d("luminosite"),
            it.b("fan"), it.b("pump"), it.b("led"), it.optString("mode").ifEmpty { null })
    }

    suspend fun control(): Control? = call("/control.json").let { if (it == "null") null else JSONObject(it) }?.let {
        Control(it.optString("mode").ifEmpty { null }, it.optBoolean("fan"), it.optBoolean("pump"), it.optBoolean("led"))
    }

    suspend fun history(): List<Point> {
        val t = call("/history.json?orderBy=%22%24key%22&limitToLast=120")
        if (t == "null") return emptyList()
        val o = JSONObject(t)
        return o.keys().asSequence().sorted().mapNotNull { k ->
            o.optJSONObject(k)?.let { Point(it.optLong("ts"), it.d("temperature"), it.d("humidite"), it.d("sol"), it.d("luminosite")) }
        }.toList()
    }

    /** PUT on the child key (HttpURLConnection has no PATCH). */
    suspend fun set(key: String, value: Any) {
        val json = if (value is String) "\"$value\"" else value.toString()
        call("/control/$key.json", "PUT", json)
    }
}
