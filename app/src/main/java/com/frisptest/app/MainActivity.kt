package com.frisptest.app

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF040D1E.toInt()
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        val html = """
            <!DOCTYPE html>
            <html lang="bn">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;background:#040d1e;color:#e6f7ff;font-family:sans-serif;display:flex;flex-direction:column;min-height:100vh;justify-content:space-between;text-align:center;">
              <div style="padding:20px;background:#0a2550;border-bottom:2px solid #00e5ff;font-size:24px;font-weight:bold;color:#00e5ff;">FR ISP TEST</div>
              <div style="padding:24px;">
                <h2 style="color:#00e5ff;">APK Build Successful!</h2>
                <p style="font-size:18px;">বাংলা পরীক্ষা: রাউটার, ব্যান্ডউইথ ও সংযুক্ত লাইন</p>
              </div>
              <div style="padding:14px;background:#0a2550;border-top:1px solid #00e5ff;color:#00e5ff;font-weight:bold;">FR ISP TEST</div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        setContentView(webView)
    }
}
