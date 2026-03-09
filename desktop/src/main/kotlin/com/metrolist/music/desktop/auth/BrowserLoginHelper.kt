package com.metrolist.music.desktop.auth

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import timber.log.Timber
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Launches Edge/Chrome with Chrome DevTools Protocol (CDP) to let the user
 * sign into YouTube Music in a real browser. Extracts cookies via CDP after
 * login, completely bypassing cookie DB encryption.
 */
object BrowserLoginHelper {

    private val json = Json { ignoreUnknownKeys = true }

    fun findBrowserExecutable(): File? {
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

        val candidates = listOf(
            // Edge (always on Windows 10/11)
            "$programFilesX86\\Microsoft\\Edge\\Application\\msedge.exe",
            "$programFiles\\Microsoft\\Edge\\Application\\msedge.exe",
            "$localAppData\\Microsoft\\Edge\\Application\\msedge.exe",
            // Chrome
            "$programFiles\\Google\\Chrome\\Application\\chrome.exe",
            "$programFilesX86\\Google\\Chrome\\Application\\chrome.exe",
            "$localAppData\\Google\\Chrome\\Application\\chrome.exe",
            // Brave
            "$programFiles\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$programFilesX86\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
            "$localAppData\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
        )

        return candidates.map(::File).firstOrNull { it.exists() }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    private fun getProfileDir(): File {
        val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
        val dir = File(appData, "Metrolist/login-browser")
        dir.mkdirs()
        return dir
    }

    /**
     * Main login flow:
     * 1. Launch Edge/Chrome with CDP enabled
     * 2. User signs into YouTube Music in the browser window
     * 3. Poll for auth cookies via CDP
     * 4. Return cookie string when login is detected
     */
    suspend fun loginWithBrowser(
        onStatus: (String) -> Unit
    ): CookieExtractResult = withContext(Dispatchers.IO) {
        val browser = findBrowserExecutable()
            ?: return@withContext CookieExtractResult.Error("No browser found (Edge or Chrome required)")

        val browserName = when {
            browser.absolutePath.contains("Edge", ignoreCase = true) -> "Edge"
            browser.absolutePath.contains("Chrome", ignoreCase = true) -> "Chrome"
            browser.absolutePath.contains("Brave", ignoreCase = true) -> "Brave"
            else -> "Browser"
        }

        val port = findFreePort()
        val profileDir = getProfileDir()

        Timber.i("Launching $browserName with CDP on port $port")
        onStatus("Launching $browserName...")

        val process = ProcessBuilder(
            browser.absolutePath,
            "--remote-debugging-port=$port",
            "--user-data-dir=${profileDir.absolutePath}",
            "--no-first-run",
            "--disable-default-apps",
            "--disable-extensions",
            "--disable-popup-blocking",
            "https://music.youtube.com"
        ).redirectErrorStream(true).start()

        try {
            // Wait for CDP to be ready
            var cdpReady = false
            for (attempt in 1..15) {
                delay(1000)
                if (!process.isAlive) {
                    return@withContext CookieExtractResult.Error("$browserName exited unexpectedly")
                }
                try {
                    val versionJson = httpGet("http://localhost:$port/json/version")
                    if (versionJson.contains("webSocketDebuggerUrl")) {
                        cdpReady = true
                        break
                    }
                } catch (_: Exception) {
                    // Not ready yet
                }
            }

            if (!cdpReady) {
                return@withContext CookieExtractResult.Error("Could not connect to $browserName DevTools")
            }

            onStatus("Sign in to YouTube Music in the $browserName window...")

            // Get the browser-level WebSocket URL
            val versionInfo = httpGet("http://localhost:$port/json/version")
            val versionObj = json.parseToJsonElement(versionInfo).jsonObject
            val wsUrl = versionObj["webSocketDebuggerUrl"]?.jsonPrimitive?.content
                ?: return@withContext CookieExtractResult.Error("No WebSocket URL from CDP")

            // Poll for cookies via CDP
            val cookies = pollForAuthCookies(wsUrl, onStatus, process)
                ?: return@withContext CookieExtractResult.Error("Login timed out or browser was closed")

            // Close the browser via CDP
            try { sendCdpCommand(wsUrl, "Browser.close", JsonObject(emptyMap())) } catch (_: Exception) {}

            CookieExtractResult.Success(cookie = cookies, browserName = "$browserName (login)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e("Browser login failed: ${e.message}")
            CookieExtractResult.Error("Browser login failed: ${e.message}")
        } finally {
            // Make sure browser is dead
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private suspend fun pollForAuthCookies(
        wsUrl: String,
        onStatus: (String) -> Unit,
        process: Process
    ): String? {
        // Poll every 3 seconds for up to 5 minutes
        val maxAttempts = 100
        for (attempt in 1..maxAttempts) {
            if (!process.isAlive) return null

            try {
                val result = sendCdpCommand(wsUrl, "Network.getAllCookies", JsonObject(emptyMap()))
                val cookiesArray = result?.get("cookies")?.jsonArray ?: continue

                val cookieMap = mutableMapOf<String, String>()
                val cookieDomain = mutableMapOf<String, String>()

                for (cookie in cookiesArray) {
                    val obj = cookie.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val value = obj["value"]?.jsonPrimitive?.content ?: continue
                    val domain = obj["domain"]?.jsonPrimitive?.content ?: continue

                    if (!domain.contains("youtube.com") && !domain.contains("google.com")) continue

                    // Prefer .youtube.com domain
                    if (name in cookieMap && cookieDomain[name]?.contains("youtube") == true) continue

                    if (value.isNotBlank()) {
                        cookieMap[name] = value
                        cookieDomain[name] = domain
                    }
                }

                // Check if we have auth cookies
                val hasSapisid = cookieMap.containsKey("SAPISID") || cookieMap.containsKey("__Secure-3PAPISID")
                val hasSid = cookieMap.containsKey("SID") || cookieMap.containsKey("__Secure-1PSID")

                if (hasSapisid && hasSid) {
                    onStatus("Login detected! Extracting cookies...")
                    // Wait a moment for all cookies to settle
                    delay(2000)
                    // Re-fetch to get final state
                    val finalResult = sendCdpCommand(wsUrl, "Network.getAllCookies", JsonObject(emptyMap()))
                    val finalCookies = finalResult?.get("cookies")?.jsonArray ?: return buildCookieString(cookieMap)

                    cookieMap.clear()
                    cookieDomain.clear()
                    for (cookie in finalCookies) {
                        val obj = cookie.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: continue
                        val value = obj["value"]?.jsonPrimitive?.content ?: continue
                        val domain = obj["domain"]?.jsonPrimitive?.content ?: continue
                        if (!domain.contains("youtube.com") && !domain.contains("google.com")) continue
                        if (name in cookieMap && cookieDomain[name]?.contains("youtube") == true) continue
                        if (value.isNotBlank()) {
                            cookieMap[name] = value
                            cookieDomain[name] = domain
                        }
                    }
                    return buildCookieString(cookieMap)
                }
            } catch (e: Exception) {
                Timber.d("Cookie poll attempt $attempt failed: ${e.message}")
            }

            delay(3000)
        }

        return null // Timed out
    }

    private fun buildCookieString(cookieMap: Map<String, String>): String {
        val priority = listOf(
            "SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID",
            "SID", "__Secure-1PSID", "__Secure-3PSID",
            "HSID", "SSID", "APISID",
            "SIDCC", "__Secure-1PSIDCC", "__Secure-3PSIDCC",
            "__Secure-1PSIDTS", "__Secure-3PSIDTS",
            "LOGIN_INFO", "PREF", "SOCS"
        )
        val parts = mutableListOf<String>()
        for (key in priority) {
            cookieMap[key]?.let { parts.add("$key=$it") }
        }
        for ((key, value) in cookieMap) {
            if (key !in priority) {
                parts.add("$key=$value")
            }
        }
        return parts.joinToString("; ")
    }

    /**
     * Send a CDP command via WebSocket and wait for the result.
     * Uses Java 21's built-in WebSocket API.
     */
    private fun sendCdpCommand(wsUrl: String, method: String, params: JsonObject): JsonObject? {
        val msgId = messageIdCounter.incrementAndGet()
        val command = buildJsonObject {
            put("id", msgId)
            put("method", method)
            put("params", params)
        }

        val messages = ConcurrentLinkedQueue<String>()
        val completeFuture = CompletableFuture<String?>()

        val client = HttpClient.newHttpClient()
        val ws = client.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), object : WebSocket.Listener {
                private val buffer = StringBuilder()

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    buffer.append(data)
                    if (last) {
                        val msg = buffer.toString()
                        buffer.clear()
                        messages.add(msg)
                        try {
                            val obj = json.parseToJsonElement(msg).jsonObject
                            if (obj["id"]?.jsonPrimitive?.intOrNull == msgId) {
                                completeFuture.complete(msg)
                            }
                        } catch (_: Exception) {}
                    }
                    webSocket.request(1)
                    return CompletableFuture.completedFuture(null)
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    completeFuture.completeExceptionally(error)
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
                    completeFuture.complete(null)
                    return CompletableFuture.completedFuture(null)
                }
            }).join()

        try {
            ws.sendText(command.toString(), true).join()
            val response = completeFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)
            if (response != null) {
                val obj = json.parseToJsonElement(response).jsonObject
                return obj["result"]?.jsonObject
            }
            return null
        } finally {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join() } catch (_: Exception) {}
        }
    }

    private val messageIdCounter = AtomicInteger(0)

    private fun httpGet(url: String): String {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body()
    }
}
