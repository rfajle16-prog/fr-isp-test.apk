package com.frisptest.app

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
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
            for (target in listOf("8.8.8.8", "192.168.0.1", "192.168.1.1")) {
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

    // Android-এ নির্ভরযোগ্য সকেট ও আইসিএমপি হাইব্রিড পিং
    private fun pingStats(host: String?, label: String): JSONObject {
        val obj = JSONObject().put("label", label)
        if (host.isNullOrBlank() || host == "null") {
            return obj.put("host", JSONObject.NULL).put("ok", false).put("error", "IP পাওয়া যায়নি")
        }
        obj.put("host", host)

        val rtts = mutableListOf<Double>()
        val targetPort = if (host == "8.8.8.8" || host == "1.1.1.1") 53 else 80

        // ৪ বার সকেট কানেকশন ট্রাই করে পিং বের করা
        for (i in 1..4) {
            val t0 = System.nanoTime()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, targetPort), 1200)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                rtts.add(ms)
            } catch (_: Exception) {
                // রাউটারে যদি ৮০ পোর্ট বন্ধ থাকে তবে ICMP ট্রাই হবে
                try {
                    val addr = InetAddress.getByName(host)
                    val t1 = System.nanoTime()
                    if (addr.isReachable(1200)) {
                        val ms = (System.nanoTime() - t1) / 1_000_000.0
                        rtts.add(ms)
                    }
                } catch (_: Exception) {}
            }
        }

        if (rtts.isEmpty()) {
            return obj.put("ok", false).put("loss", 100.0)
        }

        var diffSum = 0.0
        for (i in 1 until rtts.size) {
            diffSum += abs(rtts[i] - rtts[i - 1])
        }
        val jitter = if (rtts.size > 1) diffSum / (rtts.size - 1) else 0.0
        val loss = ((4 - rtts.size) / 4.0) * 100.0

        return obj.apply {
            put("ok", true)
            put("loss", round2(loss))
            put("avg", round2(rtts.average()))
            put("jitter", round2(jitter))
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
            JSONObject().put("ok", false).put("ms", JSONObject.NULL)
        } else {
            JSONObject().put("ok", true).put("ms", round2(times.average()))
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
                    val isp = data.optString("isp").trim()
                    val city = data.optString("city").trim()
                    val pubIp = data.optString("query").trim()
                    return JSONObject().apply {
                        put("ok", true)
                        put("display", isp)
                        put("public_ip", pubIp)
                        put("location", city)
                    }
                }
            }
        } catch (_: Exception) {}

        return JSONObject().apply {
            put("ok", false)
            put("display", "পাওয়া যায়নি")
            put("public_ip", "N/A")
            put("location", "N/A")
        }
    }

    private fun measureDownload(): JSONObject {
        return try {
            val t0 = System.nanoTime()
            val conn = (URL("https://speed.cloudflare.com/__down?bytes=15000000").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                setRequestProperty("User-Agent", UA)
            }
            var total = 0L
            val buf = ByteArray(32768)
            conn.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    total += n
                    if ((System.nanoTime() - t0) / 1_000_000_000.0 >= 5.0) break
                }
            }
            conn.disconnect()
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            val mbps = if (elapsed > 0) (total * 8.0) / (elapsed * 1_000_000.0) else 0.0
            JSONObject().apply {
                put("ok", true)
                put("mbps", round2(mbps))
                put("edge", "CF")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("mbps", JSONObject.NULL)
        }
    }

    private fun measureUpload(): JSONObject {
        val payload = ByteArray(800000) { 'x'.code.toByte() }
        return try {
            val t0 = System.nanoTime()
            val conn = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6000
                readTimeout = 8000
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("User-Agent", UA)
            }
            conn.outputStream.use { it.write(payload) }
            conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            val mbps = if (elapsed > 0) (payload.size * 8.0) / (elapsed * 1_000_000_000.0) else 0.0
            JSONObject().apply {
                put("ok", true)
                put("mbps", round2(mbps))
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("mbps", JSONObject.NULL)
        }
    }

    fun runAll(pkgMbps: Double?, onProgress: (Int, String) -> Unit): JSONObject {
        onProgress(15, "নেটওয়ার্ক টপোলজি ও সময় যাচাই হচ্ছে...")
        val timeInfo = getDhakaTimeInfo()
        val (localIp, androidGw) = getLocalAndGatewayFromAndroid()
        val hop1 = androidGw ?: "192.168.0.1"

        onProgress(35, "রাউটার ও ইন্টারনেট রেসপন্স পরীক্ষা চলছে...")
        val pRouter = pingStats(hop1, "রাউটার (Hop 1)")
        val pInet = pingStats("8.8.8.8", "ইন্টারনেট (8.8.8.8)")
        val dnsRes = dnsTest()

        onProgress(55, "ISP তথ্য ও ব্যান্ডউইথ সংগ্রহ করা হচ্ছে...")
        val ispInfo = getIspInfo()

        onProgress(75, "ডাউনলোড স্পিড টেস্ট চলছে...")
        val dlRes = measureDownload()

        onProgress(90, "আপলোড স্পিড টেস্ট চলছে...")
        val ulRes = measureUpload()

        val findings = JSONArray()
        val routerOk = pRouter.optBoolean("ok", false)
        val inetOk = dnsRes.optBoolean("ok", false) || pInet.optBoolean("ok", false)

        val overallStatus = if (inetOk && routerOk) "ভালো (Normal)" else if (inetOk) "রাউটার পিং বন্ধ / স্বাভাবিক" else "বিচ্ছিন্ন (Critical)"
        val overallLevel = if (inetOk && routerOk) "ok" else if (inetOk) "warn" else "danger"

        val routerText = if (routerOk) "স্বাভাবিক — সংযোগ ঠিক আছে" else "রাউটার পিং রেসপন্স দিচ্ছে না (তবে নেট চলতে পারে)"
        val localText = if (inetOk) "স্বাভাবিক ও চাপমুক্ত" else "লোকাল লাইন বিচ্ছিন্ন হতে পারে"
        val bwText = if (dlRes.optBoolean("ok", false)) "স্পিড সচল আছে (${dlRes.optDouble("mbps")} Mbps)" else "ব্যান্ডউইথ টেস্ট ব্যর্থ"

        val dlTxt = if (dlRes.optBoolean("ok", false)) "${dlRes.getDouble("mbps")} Mbps" else "ব্যর্থ"
        val ulTxt = if (ulRes.optBoolean("ok", false)) "${ulRes.getDouble("mbps")} Mbps" else "ব্যর্থ"

        val shortReport = buildString {
            appendLine("📡 $APP_NAME রিপোর্ট (${timeInfo.getString("short_time")} BST)")
            appendLine("• অবস্থা: $overallStatus")
            appendLine("• ISP: ${ispInfo.optString("display", "স্বাভাবিক")}")
            appendLine("• রাউটার IP: $hop1 | লোকাল IP: ${localIp ?: "N/A"}")
            appendLine("• স্পিড: ডাউনলোড $dlTxt | আপলোড $ulTxt")
            appendLine("• DNS রেসপন্স: ${dnsRes.optDouble("ms", 0.0)} ms")
        }

        val layerHealth = JSONObject().apply {
            put("router", JSONObject().put("text", routerText).put("level", if (routerOk) "ok" else "warn"))
            put("local_line", JSONObject().put("text", localText).put("level", if (inetOk) "ok" else "danger"))
            put("bandwidth", JSONObject().put("text", bwText).put("level", if (dlRes.optBoolean("ok", false)) "ok" else "warn"))
        }

        return JSONObject().apply {
            put("time_info", timeInfo)
            put("isp_info", ispInfo)
            put("local_ip", localIp ?: "পাওয়া যায়নি")
            put("hop1", hop1)
            put("hop2", "N/A")
            put("pings", JSONArray().put(pRouter).put(pInet))
            put("dns", dnsRes)
            put("download", dlRes)
            put("upload", ulRes)
            put("layer_health", layerHealth)
            put("findings", findings)
            put("overall_status", overallStatus)
            put("overall_level", overallLevel)
            put("short_report", shortReport)
        }
    }
}
