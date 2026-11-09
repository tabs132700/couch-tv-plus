package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OPTIONAL "ask" hook. OFF by default. Runs on Google Gemini, whose API has a free tier.
 * Nothing runs unless the household adds a Gemini API key (free, from aistudio.google.com) on the
 * setup page and switches "ask" on.
 *
 * Use a key made only for this TV: anyone with ADB access to the TV could read it from the app's storage.
 * On the free tier Google may use the requests to improve its products, and there is a daily request limit.
 *
 * Raw HTTPS to the Gemini API (generateContent with function calling), to keep the TV app small.
 */
object Ask {
    const val MODEL = "gemini-flash-lite-latest"
    // when the main model is busy (503) or rate-limited (429), wait a moment and try the lighter free model
    private val MODELS = listOf(MODEL, MODEL, "gemini-flash-latest")
    private fun endpoint(model: String) = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    val enabled get() = Store.bool("ask_enabled") && !Store.str("ask_key").isNullOrEmpty()

    private val TOOLS = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
        .put(tool("play", "Find a show or film and start it in the right streaming app on the TV.",
            JSONObject().put("title", str("Show or film name")).put("season", int()).put("episode", int()).put("service", str("Optional app id, for example netflix")), listOf("title")))
        .put(tool("launch", "Open an app on the TV by id (netflix, youtube, disney-plus, prime-video, ...).",
            JSONObject().put("app", str("App id or Android package")), listOf("app")))
        .put(tool("key", "Press a remote key: up, down, left, right, ok, back, home, play_pause, volume_up, volume_down, mute.",
            JSONObject().put("key", str("Key name")), listOf("key")))
        .put(tool("now", "What is on the TV right now.", JSONObject(), emptyList()))))

    fun ask(text: String, who: String?): JSONObject {
        val key = Store.str("ask_key") ?: throw ApiError(501, "Ask is off in on-TV mode.")
        val started = System.currentTimeMillis()
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))))
        val actions = JSONArray()
        val system = "You control a TV through tools. Do what the person asks with the fewest tool calls, then reply in one or two short plain sentences, " +
            "in the language the person used. Country: ${Store.country()}." + (who?.let { " Watching: $it." } ?: "")
        for (round in 0 until 5) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents).put("tools", TOOLS)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
            val res = call(key, body)
            val content = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return JSONObject().put("ok", true).put("reply", "Done.").put("actions", actions).put("seconds", secs(started))
            val parts = content.optJSONArray("parts") ?: JSONArray()
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val calls = (0 until parts.length()).map { parts.getJSONObject(it) }.mapNotNull { it.optJSONObject("functionCall") }
            if (calls.isEmpty()) {
                val reply = (0 until parts.length()).map { parts.getJSONObject(it) }.joinToString(" ") { it.optString("text") }.trim()
                return JSONObject().put("ok", true).put("reply", reply.ifEmpty { "Done." }).put("actions", actions).put("seconds", secs(started))
            }
            val results = JSONArray()
            for (fc in calls) {
                val name = fc.getString("name")
                val input = fc.optJSONObject("args") ?: JSONObject()
                val (ok, out) = try { true to runTool(name, input, who) } catch (e: Exception) { false to (e.message ?: "failed") }
                actions.put(JSONObject().put("tool", name).put("args", input).put("ok", ok))
                results.put(JSONObject().put("functionResponse", JSONObject().put("name", name)
                    .put("response", JSONObject().put(if (ok) "result" else "error", out))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", results))
        }
        return JSONObject().put("ok", true).put("reply", "Done.").put("actions", actions).put("seconds", secs(started))
    }

    private fun secs(started: Long) = (System.currentTimeMillis() - started) / 1000.0

    private fun runTool(name: String, a: JSONObject, who: String?): String = when (name) {
        "play" -> Finder.play(a.getString("title"), a.optInt("season").takeIf { it > 0 }, a.optInt("episode").takeIf { it > 0 }, a.optString("service").ifEmpty { null }, who).toString()
        "launch" -> { val app = Apps.BY_ID[a.getString("app")]; Tv.launchPackage(app?.pkg ?: a.getString("app")); "opened" }
        "key" -> Tv.key(a.getString("key"))
        "now" -> Now.payload().toString()
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    private fun call(key: String, body: JSONObject): JSONObject {
        var last: ApiError? = null
        for ((i, model) in MODELS.withIndex()) {
            try { return callOnce(model, key, body) } catch (e: java.net.SocketTimeoutException) {
                last = ApiError(504, "Gemini took too long. Try again in a minute.")
                continue
            } catch (e: ApiError) {
                last = e
                if (!(e.message ?: "").let { it.contains(" 503") || it.contains(" 429") || it.contains(" 500") }) throw e
                Thread.sleep(if (i == 0) 1200L else 600L)
            }
        }
        throw last ?: ApiError(502, "Gemini is busy. Try again in a minute.")
    }

    private fun callOnce(model: String, key: String, body: JSONObject): JSONObject {
        val c = URL(endpoint(model)).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        c.disconnect()
        if (code !in 200..299) throw ApiError(502, "Gemini API error $code: " + (runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull() ?: text.take(120)))
        return JSONObject(text)
    }

    // Gemini: a function with no arguments has no "parameters" at all
    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val f = JSONObject().put("name", name).put("description", desc)
        if (props.length() > 0) f.put("parameters", JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray(required)))
        return f
    }
    private fun str(d: String) = JSONObject().put("type", "STRING").put("description", d)
    private fun int() = JSONObject().put("type", "INTEGER")
}
