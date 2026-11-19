package com.frisptest.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF040D1E.toInt()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(WebBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(webView)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    inner class WebBridge {
        @JavascriptInterface
        fun startTest(pkgInput: String) {
            val pkgMbps = pkgInput.trim().toDoubleOrNull()?.takeIf { it > 0.0 && it <= 10000.0 }
            if (isRunning.compareAndSet(false, true)) {
                executor.execute {
                    try {
                        val resJson = DiagnosticEngine(this@MainActivity).runAll(pkgMbps) { pct, msg ->
                            runOnUiThread {
                                webView.evaluateJavascript(
                                    "window.onNativeProgress($pct, ${JSONObject.quote(msg)})",
                                    null
                                )
                            }
                        }
                        val jsonStr = resJson.toString()
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.onNativeResult(${JSONObject.quote(jsonStr)})",
                                null
                            )
                        }
                    } catch (e: Exception) {
                        val errMsg = e.message ?: "Unknown error"
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "window.onNativeError(${JSONObject.quote(errMsg)})",
                                null
                            )
                        }
                    } finally {
                        isRunning.set(false)
                    }
                }
            }
        }

        @JavascriptInterface
        fun copyReport(text: String): Boolean {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("FR ISP TEST Report", text)
                cm.setPrimaryClip(clip)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
