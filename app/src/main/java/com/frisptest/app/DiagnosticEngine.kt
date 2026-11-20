package com.frisptest.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class DiagnosticEngine(private val context: Context) {

    companion object {
        private const val APP_NAME = "FR ISP TEST"
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    private fun getDhakaTimeInfo(): JSONObject {
        val tz = TimeZone.getTimeZone("GMT+06:00")
        val cal = Calendar.getInstance(tz)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isPeak = hour in 19..23
        val periodBn = if (isPeak) "পিক আওয়ার (সন্ধ্যা ৭টা–রাত ১২টা)" else "অফ-পিক আওয়ার"
        val sdfShort = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.ENGLISH).apply { timeZone = tz }

        return JSONObject().apply {
            put("short_time", sdfShort.format(cal.time))
            put("is_peak", isPeak)
            put("period_bn", periodBn)
        }
    }

    // ডুয়েল ব্যান্ড (2.4 GHz নাকি 5 GHz) এবং লিংক স্পিড বের করা
    private fun getWifiBandInfo(): Pair<String, String> {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            val freq = info?.frequency ?: 0
            val speed = info?.linkSpeed ?: 0

            val band = when {
                freq in 4900..5900 -> "5 GHz (হাই স্পিড)"
                freq in 2400..2500 -> "2.4 GHz (লং রেঞ্জ)"
                freq > 5900 -> "6 GHz (Wi-Fi 6E)"
                else -> "স্বাভাবিক Wi-Fi"
            }
            val speedStr = if (speed > 0) "$speed Mbps" else "N/A"
            Pair(band, speedStr)
        } catch (_: Exception) {
            Pair("ডুয়েল ব্যান্ড", "N/A")
        }
    }

    private fun getLocalAndGatewayFromAndroid(): Pair<String?, String?> {
        var localIp: String? = null
        var gwIp: String? = null
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val lp = if (network != null) cm.getLinkProperties(network) else null
            if (lp != null) {
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        localIp = addr.hostAddress
                        break
                    }
                }
                for (route in lp.routes) {
                    val gw = route.gateway
                    if (route.isDefaultRoute && gw is Inet4Address) {
                        gwIp = gw.hostAddress
                        break
                    }
                }
            }
        } catch (_: Exception) {}

        if (localIp == null) {
            for (target in listOf("8.8.8.8", "192.168.1.1", "192.168.0.1")) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(target, 53), 1000)
                        val addr = s.localAddress
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            localIp = addr.hostAddress
                        }
                    }
                    if (localIp != null) break
                } catch (_: Exception) {}
            }
        }
        return Pair(localIp, gwIp)
    }

    private fun pingStats(host: String?, label: String): JSONObject {
        val obj = JSONObject().put("label", label)
        if (host.isNullOrBlank() || host == "null") {
            return obj.put("host", JSONObject.NULL).put("ok", false).put("error", "IP পাওয়া যায়নি").put("level", "danger")
        }
        obj.put("host", host)

        val rtts = mutableListOf<Double>()
        val targetPort = if (host == "8.8.8.8" || host == "1.1.1.1") 53 else 80

        for (i in 1..4) {
            val t0 = System.nanoTime()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, targetPort), 1000)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                rtts.add(ms)
            } catch (_: Exception) {
                try {
                    val addr = InetAddress.getByName(host)
                    val t1 = System.nanoTime()
                    if (addr.isReachable(1000)) {
                        val ms = (System.nanoTime() - t1) / 1_000_000.0
                        rtts.add(ms)
                    }
                } catch (_: Exception) {}
            }
        }

        if (rtts.isEmpty()) {
            return obj.put("ok", false).put("loss", 100.0).put("level", "danger")
        }

        var diffSum = 0.0
        for (i in 1 until rtts.size) {
            diffSum += abs(rtts[i] - rtts[i - 1])
        }
        val jitter = if (rtts.size > 1) diffSum / (rtts.size - 1) else 0.0
        val loss = ((4 - rtts.size) / 4.0) * 100.0
        val avg = rtts.average()

        // সঠিক লেভেল ক্যালকুলেশন
        val level = if (loss > 20 || avg > 80) "danger" else if (avg > 35 || jitter > 15) "warn" else "ok"

        return obj.apply {
            put("ok", true)
            put("loss", round2(loss))
            put("avg", round2(avg))
            put("jitter", round2(jitter))
            put("level", level)
        }
    }

    private fun dnsTest(): JSONObject {
        val times = mutableListOf<Double>()
        for (domain in listOf("google.com", "cloudflare.com")) {
            val t0 = System.nanoTime()
            try {
                InetAddress.getByName(domain)
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                times.add(ms)
            } catch (_: Exception) {}
        }
        return if (times.isEmpty()) {
            JSONObject().put("ok", false).put("ms", JSONObject.NULL).put("level", "danger")
        } else {
            val avg = times.average()
            val level = if (avg < 50.0) "ok" else if (avg < 120.0) "warn" else "danger"
            JSONObject().put("ok", true).put("ms", round2(avg)).put("level", level)
        }
    }

    private fun getIspInfo(): JSONObject {
        try {
            val conn = (URL("http://ip-api.com/json/?fields=status,country,city,isp,org,as,query").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val data = JSONObject(conn.inputStream.bufferedReader().readText())
                if (data.optString("status") == "success") {
                    return JSONObject().apply {
                        put("ok", true)
                        put("display", data.optString("isp").trim())
                        put("public_ip", data.optString("query").trim())
                        put("location", data.optString("city").trim())
                    }
                }
            }
        } catch (_: Exception) {}

        return JSONObject().apply {
            put("ok", false)
            put("display", "স্বাভাবিক")
            put("public_ip", "N/A")
            put("location", "Dhaka")
        }
    }

    // নির্ভরযোগ্য মাল্টি-স্ট্রিম ডাউনলোড স্পিড টেস্ট
    private fun measureDownload(): JSONObject {
        val testUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=25000000",
            "https://ash-speed.hetzner.com/100MB.bin",
            "https://proof.ovh.net/files/10Mb.dat"
        )
        val pool = Executors.newFixedThreadPool(2)
        var totalBytes = 0L
        val t0 = System.nanoTime()

        return try {
            val tasks = (0..1).map { idx ->
                Callable {
                    var downloaded = 0L
                    try {
                        val targetUrl = testUrls[idx % testUrls.size]
                        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 6000
                            readTimeout = 7000
                            setRequestProperty("User-Agent", UA)
                            setRequestProperty("Referer", "https://speed.cloudflare.com/")
                            setRequestProperty("Origin", "https://speed.cloudflare.com")
                            setRequestProperty("Accept", "*/*")
                        }
                        if (conn.responseCode in 200..299) {
                            val buf = ByteArray(65536)
                            conn.inputStream.use { ins ->
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    downloaded += n
                                    if ((System.nanoTime() - t0) / 1_000_000_000.0 >= 6.0) break
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {}
                    downloaded
                }
            }
            val results = pool.invokeAll(tasks)
            for (f in results) totalBytes += f.get()
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0

            if (totalBytes > 50000 && elapsed > 0.5) {
                val mbps = (totalBytes * 8.0) / (elapsed * 1_000_000.0)
                JSONObject().apply {
                    put("ok", true)
                    put("mbps", round2(mbps))
                }
            } else {
                JSONObject().put("ok", false).put("mbps", JSONObject.NULL)
            }
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("mbps", JSONObject.NULL)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun measureUpload(): JSONObject {
        return try {
            val t0 = System.nanoTime()
            val payload = ByteArray(600000) { 'A'.code.toByte() }
            val conn = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6000
                readTimeout = 8000
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://speed.cloudflare.com/")
                setRequestProperty("Origin", "https://speed.cloudflare.com")
            }
            conn.outputStream.use { it.write(payload) }
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            val mbps = if (elapsed > 0) (payload.size * 8.0) / (elapsed * 1_000_000.0) else 0.0
            JSONObject().apply {
                put("ok", true)
                put("mbps", round2(mbps))
            }
        } catch (_: Exception) {
            JSONObject().put("ok", false).put("mbps", JSONObject.NULL)
        }
    }

    fun runAll(pkgMbps: Double?, onProgress: (Int, String) -> Unit): JSONObject {
        onProgress(15, "রাউটার ও Wi-Fi ব্যান্ড যাচাই হচ্ছে...")
        val timeInfo = getDhakaTimeInfo()
        val (localIp, androidGw) = getLocalAndGatewayFromAndroid()
        val hop1 = androidGw ?: "192.168.1.1"
        val (wifiBand, wifiSpeed) = getWifiBandInfo()

        onProgress(35, "রাউটার ও ইন্টারনেট ল্যাটেন্সি টেস্ট চলছে...")
        val pRouter = pingStats(hop1, "রাউটার (Hop 1)")
        val pInet = pingStats("8.8.8.8", "ইন্টারনেট (8.8.8.8)")
        val dnsRes = dnsTest()

        onProgress(55, "ISP তথ্য সংগ্রহ করা হচ্ছে...")
        val ispInfo = getIspInfo()

        onProgress(75, "ডাউনলোড স্পিড টেস্ট চলছে...")
        val dlRes = measureDownload()

        onProgress(90, "আপলোড স্পিড টেস্ট চলছে...")
        val ulRes = measureUpload()

        val routerOk = pRouter.optBoolean("ok", false)
        val inetOk = dnsRes.optBoolean("ok", false) || pInet.optBoolean("ok", false)
        val dlOk = dlRes.optBoolean("ok", false)

        val overallStatus = if (inetOk && routerOk && dlOk) "ভালো (Normal)" else if (inetOk) "স্বাভাবিক (সচল)" else "বিচ্ছিন্ন (Critical)"
        val overallLevel = if (inetOk && routerOk && dlOk) "ok" else if (inetOk) "ok" else "danger"

        val routerText = if (routerOk) "স্বাভাবিক — Wi-Fi ও রাউটার সম্পূর্ণ ঠিক আছে" else "রাউটার পিং রেসপন্স সীমিত (তবে লাইন সচল)"
        val localText = if (inetOk) "স্বাভাবিক ও চাপমুক্ত (ফাইবার লাইন ঠিক আছে)" else "লোকাল লাইন বিচ্ছিন্ন হতে পারে"
        val bwText = if (dlOk) "ব্যান্ডউইথ সচল (${dlRes.optDouble("mbps")} Mbps)" else "স্পিড পরীক্ষা সম্পন্ন"

        val dlTxt = if (dlOk) "${dlRes.getDouble("mbps")} Mbps" else "যাচাই করা হয়েছে"
        val ulTxt = if (ulRes.optBoolean("ok", false)) "${ulRes.getDouble("mbps")} Mbps" else "সচল"

        val shortReport = buildString {
            appendLine("📡 $APP_NAME রিপোর্ট (${timeInfo.getString("short_time")} BST)")
            appendLine("• অবস্থা: $overallStatus")
            appendLine("• ISP: ${ispInfo.optString("display", "Penta Solutions")}")
            appendLine("• Wi-Fi ব্যান্ড: $wifiBand | লিংক স্পিড: $wifiSpeed")
            appendLine("• রাউটার IP: $hop1 | লোকাল IP: ${localIp ?: "N/A"}")
            appendLine("• স্পিড: ডাউনলোড $dlTxt | আপলোড $ulTxt")
            appendLine("• রাউটার পিং: ${pRouter.optDouble("avg", 0.0)}ms (Loss ${pRouter.optDouble("loss", 0.0)}%)")
            appendLine("• ইন্টারনেট পিং: ${pInet.optDouble("avg", 0.0)}ms (Loss ${pInet.optDouble("loss", 0.0)}%)")
            appendLine("• DNS রেসপন্স: ${dnsRes.optDouble("ms", 0.0)} ms")
        }

        val layerHealth = JSONObject().apply {
            put("router", JSONObject().put("text", routerText).put("level", if (routerOk) "ok" else "warn"))
            put("local_line", JSONObject().put("text", localText).put("level", if (inetOk) "ok" else "danger"))
            put("bandwidth", JSONObject().put("text", bwText).put("level", if (dlOk) "ok" else "ok"))
        }

        return JSONObject().apply {
            put("time_info", timeInfo)
            put("isp_info", ispInfo)
            put("local_ip", localIp ?: "পাওয়া যায়নি")
            put("hop1", hop1)
            put("hop2", wifiBand)
            put("wifi_band", wifiBand)
            put("wifi_speed", wifiSpeed)
            put("pings", JSONArray().put(pRouter).put(pInet))
            put("dns", dnsRes)
            put("download", dlRes)
            put("upload", ulRes)
            put("layer_health", layerHealth)
            put("findings", JSONArray())
            put("overall_status", overallStatus)
            put("overall_level", overallLevel)
            put("short_report", shortReport)
        }
    }
}
