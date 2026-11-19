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

        val sdfFull = SimpleDateFormat("yyyy-MM-dd hh:mm:ss a '(BST)'", Locale.ENGLISH).apply { timeZone = tz }
        val sdfShort = SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.ENGLISH).apply { timeZone = tz }

        return JSONObject().apply {
            put("dt_str", sdfFull.format(cal.time))
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
        } catch (_: Exception) {
        }

        if (localIp == null) {
            for (target in listOf("8.8.8.8", "192.168.0.1", "192.168.1.1")) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(target, 80), 1500)
                        val addr = s.localAddress
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            localIp = addr.hostAddress
                        }
                    }
                    if (localIp != null) break
                } catch (_: Exception) {
                }
            }
        }
        return Pair(localIp, gwIp)
    }

    private fun runPingCommand(args: List<String>): Pair<Int, String> {
        return try {
            val cmd = mutableListOf("/system/bin/ping").apply { addAll(args) }
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).readText()
            val rc = proc.waitFor()
            Pair(rc, out)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "")
        }
    }

    private fun probeHop(ttl: Int, target: String = "8.8.8.8"): String? {
        val (_, out) = runPingCommand(listOf("-c", "1", "-t", ttl.toString(), "-W", "2", target))
        val mFrom = Regex("""From\s+([\d.]+)""").find(out)
        if (mFrom != null) return mFrom.groupValues[1]
        val mReply = Regex("""from\s+([\d.]+)""").find(out)
        if (mReply != null) return mReply.groupValues[1]
        return null
    }

    private fun pingStats(host: String?, label: String): JSONObject {
        val obj = JSONObject().put("label", label)
        if (host.isNullOrBlank()) {
            return obj.put("host", JSONObject.NULL).put("ok", false).put("error", "IP পাওয়া যায়নি")
        }
        obj.put("host", host)

        var (_, out) = runPingCommand(listOf("-c", "8", "-i", "0.3", "-W", "2", host))
        if (!out.contains("bytes from") && (out.contains("cannot", true) || out.contains("permitted", true) || out.contains("invalid", true))) {
            val retry = runPingCommand(listOf("-c", "6", "-W", "2", host))
            out = retry.second
        }

        val rtts = Regex("""time=([\d.]+)\s*ms""").findAll(out).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val lossMatch = Regex("""(\d+(?:\.\d+)?)%\s*packet loss""").find(out)
        val loss = lossMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: if (rtts.isEmpty()) 100.0 else 0.0

        if (rtts.isEmpty()) {
            return obj.put("ok", false).put("loss", round2(loss))
        }

        var diffSum = 0.0
        for (i in 1 until rtts.size) {
            diffSum += abs(rtts[i] - rtts[i - 1])
        }
        val jitter = if (rtts.size > 1) diffSum / (rtts.size - 1) else 0.0

        return obj.apply {
            put("ok", true)
            put("loss", round2(loss))
            put("min", round2(rtts.min()))
            put("avg", round2(rtts.average()))
            put("max", round2(rtts.max()))
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
            } catch (_: Exception) {
            }
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
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                val data = JSONObject(body)
                if (data.optString("status") == "success") {
                    val isp = data.optString("isp").trim()
                    val org = data.optString("org").trim()
                    val asRaw = data.optString("as").trim()
                    val asNum = asRaw.split(" ").firstOrNull() ?: ""
                    val city = data.optString("city").trim()
                    val country = data.optString("country").trim()
                    val pubIp = data.optString("query").trim()

                    val parts = mutableListOf<String>()
                    if (isp.isNotEmpty()) parts.add(isp)
                    if (org.isNotEmpty() && !org.equals(isp, ignoreCase = true)) parts.add(org)
                    var display = if (parts.isNotEmpty()) parts.joinToString(" / ") else asRaw.ifEmpty { "অজানা" }
                    if (asNum.isNotEmpty() && !display.contains(asNum)) {
                        display = "$display ($asNum)"
                    }
                    val loc = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "অজানা" }
                    return JSONObject().apply {
                        put("ok", true)
                        put("display", display)
                        put("public_ip", pubIp.ifEmpty { "N/A" })
                        put("location", loc)
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            val conn = (URL("https://ipinfo.io/json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val data = JSONObject(conn.inputStream.bufferedReader().readText())
                val org = data.optString("org").trim()
                val city = data.optString("city").trim()
                val country = data.optString("country").trim()
                val pubIp = data.optString("ip").trim()
                val loc = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "অজানা" }
                return JSONObject().apply {
                    put("ok", true)
                    put("display", org.ifEmpty { "অজানা" })
                    put("public_ip", pubIp.ifEmpty { "N/A" })
                    put("location", loc)
                }
            }
        } catch (_: Exception) {
        }

        return JSONObject().apply {
            put("ok", false)
            put("display", "পাওয়া যায়নি")
            put("public_ip", "N/A")
            put("location", "N/A")
        }
    }

    private fun measureDownload(): JSONObject {
        val pool = Executors.newFixedThreadPool(3)
        return try {
            val t0 = System.nanoTime()
            val tasks = (1..3).map {
                Callable {
                    var total = 0L
                    var cfRay: String? = null
                    val start = System.nanoTime()
                    val conn = (URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 10000
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty("Referer", "https://speed.cloudflare.com/")
                        setRequestProperty("Origin", "https://speed.cloudflare.com")
                    }
                    if (conn.responseCode >= 400) throw RuntimeException("HTTP ${conn.responseCode}")
                    cfRay = conn.getHeaderField("cf-ray")
                    val buf = ByteArray(65536)
                    conn.inputStream.use { ins ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            total += n
                            if ((System.nanoTime() - start) / 1_000_000_000.0 >= 6.0) break
                        }
                    }
                    conn.disconnect()
                    Pair(total, cfRay)
                }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            val totalBytes = results.sumOf { it.first }
            val cfRay = results.firstNotNullOfOrNull { it.second } ?: "CF"
            val edge = if (cfRay.contains("-")) cfRay.substringAfterLast("-") else cfRay
            val mbps = if (elapsed > 0) (totalBytes * 8.0) / (elapsed * 1_000_000.0) else 0.0
            JSONObject().apply {
                put("ok", true)
                put("mbps", round2(mbps))
                put("edge", edge)
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("mbps", JSONObject.NULL).put("error", e.message ?: "Error")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun measureUpload(): JSONObject {
        val pool = Executors.newFixedThreadPool(2)
        val payload = ByteArray(1_500_000) { 'x'.code.toByte() }
        return try {
            val t0 = System.nanoTime()
            val tasks = (1..2).map {
                Callable {
                    val conn = (URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 10000
                        readTimeout = 20000
                        setFixedLengthStreamingMode(payload.size)
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty("Referer", "https://speed.cloudflare.com/")
                        setRequestProperty("Origin", "https://speed.cloudflare.com")
                    }
                    conn.outputStream.use { it.write(payload) }
                    if (conn.responseCode >= 400) throw RuntimeException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    payload.size.toLong()
                }
            }
            val totalBytes = pool.invokeAll(tasks).sumOf { it.get() }
            val elapsed = (System.nanoTime() - t0) / 1_000_000_000.0
            val mbps = if (elapsed > 0) (totalBytes * 8.0) / (elapsed * 1_000_000.0) else 0.0
            JSONObject().apply {
                put("ok", true)
                put("mbps", round2(mbps))
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("mbps", JSONObject.NULL).put("error", e.message ?: "Error")
        } finally {
            pool.shutdownNow()
        }
    }

    fun runAll(pkgMbps: Double?, onProgress: (Int, String) -> Unit): JSONObject {
        onProgress(10, "[১/৬] ঢাকা সময় ও পিক আওয়ার যাচাই হচ্ছে...")
        val timeInfo = getDhakaTimeInfo()

        onProgress(22, "[২/৬] রাউটার ও নেটওয়ার্ক টপোলজি খোঁজা হচ্ছে...")
        val (localIp, androidGw) = getLocalAndGatewayFromAndroid()
        val hop1 = androidGw ?: probeHop(1)
        val rawHop2 = probeHop(2)
        val hop2 = if (rawHop2 == hop1 || rawHop2 == "8.8.8.8") null else rawHop2
        val rawHop3 = probeHop(3)
        val hop3 = if (rawHop3 == hop1 || rawHop3 == hop2 || rawHop3 == "8.8.8.8") null else rawHop3

        onProgress(40, "[৩/৬] পিং, জিটার ও লস টেস্ট চলছে (রাউটার: ${hop1 ?: "N/A"})...")
        val pRouter = pingStats(hop1, "রাউটার (Hop 1)")
        val pHop2 = pingStats(hop2, "ONU / গেটওয়ে (Hop 2)")
        val pInet = pingStats("8.8.8.8", "ইন্টারনেট (8.8.8.8)")
        val dnsRes = dnsTest()

        val inetOk = pInet.optBoolean("ok", false)
        val ispInfo: JSONObject
        val dlRes: JSONObject
        val ulRes: JSONObject

        if (inetOk) {
            onProgress(58, "[৪/৬] ISP ও ব্যান্ডউইথ কোম্পানির তথ্য খোঁজা হচ্ছে...")
            ispInfo = getIspInfo()
            onProgress(74, "[৫/৬] ডাউনলোড স্পিড টেস্ট চলছে (প্রায় ৬ সেকেন্ড)...")
            dlRes = measureDownload()
            onProgress(90, "[৬/৬] আপলোড স্পিড টেস্ট চলছে...")
            ulRes = measureUpload()
        } else {
            ispInfo = JSONObject().put("ok", false).put("display", "ইন্টারনেট না থাকায় পাওয়া যায়নি").put("public_ip", "N/A").put("location", "N/A")
            dlRes = JSONObject().put("ok", false).put("mbps", JSONObject.NULL).put("error", "ইন্টারনেট নেই")
            ulRes = JSONObject().put("ok", false).put("mbps", JSONObject.NULL).put("error", "ইন্টারনেট নেই")
        }

        val findings = JSONArray()
        val shortIssues = mutableListOf<String>()

        fun addFinding(title: String, desc: String, level: String) {
            findings.put(JSONObject().put("title", title).put("desc", desc).put("level", level))
        }

        val routerText: String
        val routerLevel: String
        val localText: String
        val localLevel: String
        val bwText: String
        val bwLevel: String
        val overallStatus: String
        val overallLevel: String

        if (localIp == null) {
            routerText = "Wi-Fi / রাউটারে সংযুক্ত নয়"
            routerLevel = "danger"
            localText = "যাচাই করা যায়নি (সংযোগ নেই)"
            localLevel = "danger"
            bwText = "যাচাই করা যায়নি (সংযোগ নেই)"
            bwLevel = "danger"
            addFinding("রাউটার / Wi-Fi সংযোগ বিচ্ছিন্ন", "ফোন কোনো Wi-Fi/নেটওয়ার্কে যুক্ত নয় অথবা Local IP পাওয়া যায়নি।", "danger")
            shortIssues.add("ফোন Wi-Fi/রাউটারে কানেক্ট নেই")
            overallStatus = "বিচ্ছিন্ন"
            overallLevel = "danger"
        } else {
            // ১. রাউটারের অবস্থা
            val rOk = pRouter.optBoolean("ok", false)
            val rLoss = pRouter.optDouble("loss", 100.0)
            val rAvg = pRouter.optDouble("avg", 0.0)
            val rJitter = pRouter.optDouble("jitter", 0.0)

            if (!rOk) {
                routerText = "সমস্যা — রাউটার রেসপন্স করছে না (রাউটার হ্যাং বা চার্জার চেক করুন)"
                routerLevel = "danger"
                addFinding("রাউটার বা রাউটারের চার্জার/পাওয়ার সমস্যা", "রাউটার IP-তে কোনো পিং রেসপন্স পাওয়া যায়নি। রাউটার হ্যাং হয়ে থাকতে পারে অথবা রাউটারের চার্জার/পাওয়ারে সমস্যা থাকতে পারে।", "danger")
                shortIssues.add("রাউটার রেসপন্স করছে না (রাউটার/চার্জার চেক করুন)")
            } else if (rLoss > 0) {
                routerText = "সমস্যা — রাউটারে প্যাকেট লস ($rLoss%) (Wi-Fi/রাউটার/চার্জার চেক করুন)"
                routerLevel = "danger"
                addFinding("রাউটার / Wi-Fi প্যাকেট লস সমস্যা", "রাউটারে (${pRouter.optString("host")}) $rLoss% প্যাকেট লস হচ্ছে (Avg: ${rAvg}ms, Jitter: ${rJitter}ms)। Wi-Fi সিগন্যাল দুর্বল অথবা রাউটার/চার্জারে সমস্যা থাকতে পারে।", "danger")
                shortIssues.add("রাউটারে লস ($rLoss%)")
            } else if (rJitter > 15 || rAvg > 20) {
                routerText = "সমস্যা/উচ্চ চাপ — Wi-Fi সিগন্যাল দুর্বল বা রাউটারে লোড বেশি (Avg ${rAvg}ms, Jitter ${rJitter}ms)"
                routerLevel = "warn"
                addFinding("রাউটার / Wi-Fi সিগন্যাল বা লোড সমস্যা", "রাউটারে (${pRouter.optString("host")}) পিং/জিটার বেশি (Avg: ${rAvg}ms, Jitter: ${rJitter}ms)। একসাথে অনেক ডিভাইস চললে, দূরত্ব বেশি হলে বা রাউটার গরম হলে এমন হয়।", "warn")
                shortIssues.add("রাউটার/Wi-Fi চাপ বেশি (Jitter ${rJitter}ms)")
            } else if (rJitter > 7 || rAvg > 10) {
                routerText = "সামান্য চাপ — Wi-Fi ইন্টারফেয়ারেন্স বা রাউটার লোড (Avg ${rAvg}ms, Jitter ${rJitter}ms)"
                routerLevel = "warn"
                addFinding("রাউটারে সামান্য Wi-Fi/লোড চাপ", "রাউটারে বড় সমস্যা নেই, তবে সামান্য জিটার (${rJitter}ms) ও পিং (${rAvg}ms) আছে। রাউটারের কাছাকাছি থাকলে বা রিস্টার্ট দিলে আরও ভালো হতে পারে।", "warn")
                shortIssues.add("রাউটারে সামান্য Wi-Fi চাপ (Jitter ${rJitter}ms)")
            } else {
                routerText = "ভালো ও স্বাভাবিক — কোনো সমস্যা নেই (Avg ${rAvg}ms, Jitter ${rJitter}ms)"
                routerLevel = "ok"
            }

            // ২. লোকাল লাইনের চাপ
            val h2Host = pHop2.optString("host", "")
            val h2Ok = pHop2.optBoolean("ok", false)
            val h2Loss = pHop2.optDouble("loss", 100.0)
            val h2Avg = pHop2.optDouble("avg", 0.0)
            val h2Max = pHop2.optDouble("max", 0.0)
            val h2Jitter = pHop2.optDouble("jitter", 0.0)

            if (!rOk) {
                localText = "যাচাই করা যায়নি (রাউটার থেকে রেসপন্স নেই)"
                localLevel = "danger"
            } else if (h2Host.isEmpty() || h2Host == "null") {
                if (!inetOk) {
                    localText = "সমস্যা — আপস্ট্রিম/লোকাল লাইন বিচ্ছিন্ন (CAT5/6 কেবল, ONU বা ফাইবার চেক করুন)"
                    localLevel = "danger"
                } else {
                    localText = "স্বাভাবিক ও চাপমুক্ত (ডাইরেক্ট রাউটার-টু-ISP সংযোগ)"
                    localLevel = "ok"
                }
            } else if (!h2Ok) {
                localText = "গুরুতর সমস্যা — লোকাল লাইন বিচ্ছিন্ন ($h2Host নেই; কেবল/ONU/চার্জার চেক করুন)"
                localLevel = "danger"
                addFinding("CAT5/CAT6 কেবল, ONU অথবা ONU চার্জার সমস্যা", "রাউটার পর্যন্ত সংযোগ আছে, কিন্তু পরের ধাপে ($h2Host) রেসপন্স নেই। Router থেকে ONU-র CAT5/CAT6 কেবল ঢিলা/নষ্ট হতে পারে অথবা ONU/চার্জার বন্ধ থাকতে পারে।", "danger")
                shortIssues.add("লোকাল লাইন বিচ্ছিন্ন (CAT5/6 কেবল বা ONU চেক করুন)")
            } else if (h2Loss > 0 || h2Jitter > 25 || h2Max > 100) {
                localText = "উচ্চ চাপ/সমস্যা — কেবল/ONU বা ফাইবার দুর্বল হতে পারে (Max ${h2Max}ms, Jitter ${h2Jitter}ms, Loss $h2Loss%)"
                localLevel = "warn"
                addFinding("লোকাল লাইন চাপ (CAT5/6 কেবল, ONU বা ফাইবার সমস্যা)", "Hop 2-তে ($h2Host) স্পাইক বা লস দেখা যাচ্ছে (Avg: ${h2Avg}ms, Max: ${h2Max}ms, Jitter: ${h2Jitter}ms, Loss: $h2Loss%)। CAT5/CAT6 কেবল, ONU/চার্জার অথবা ফাইবার পাওয়ার (-27dBm এর নিচে) চেক করা প্রয়োজন।", "warn")
                shortIssues.add("লোকাল লাইনে উচ্চ চাপ (Hop2 Max ${h2Max}ms)")
            } else if (h2Jitter > 12 || h2Avg > 15) {
                localText = "মাঝারি চাপ — ONU/লোকাল লাইনে কিছুটা লোড আছে (Avg ${h2Avg}ms, Jitter ${h2Jitter}ms)"
                localLevel = "warn"
            } else {
                localText = "স্বাভাবিক ও চাপমুক্ত (Avg ${h2Avg}ms, Jitter ${h2Jitter}ms, Loss 0%)"
                localLevel = "ok"
            }

            // ৩. ইন্টারনেট ও DNS
            val iLoss = pInet.optDouble("loss", 100.0)
            val iJitter = pInet.optDouble("jitter", 0.0)
            if (!inetOk) {
                addFinding("ইন্টারনেট বিচ্ছিন্ন (ফাইবার কাটা / ONU LOS / ISP ডাউন)", "রাউটার কাজ করলেও ইন্টারনেটে (8.8.8.8) কোনো সংযোগ নেই। ONU-তে লাল LOS বাতি জ্বলছে কি না দেখুন অথবা ISP-র লাইন ডাউন।", "danger")
                shortIssues.add("ইন্টারনেট সংযোগ নেই (ONU LOS লাল বাতি / ফাইবার / ISP চেক করুন)")
            } else if (iLoss > 2 || iJitter > 30) {
                addFinding("ফাইবার পাওয়ার বা ISP নেটওয়ার্ক অস্থিরতা", "ইন্টারনেটে (8.8.8.8) প্যাকেট লস ($iLoss%) বা উচ্চ জিটার (${iJitter}ms) পাওয়া যাচ্ছে। এটি দুর্বল ফাইবার সিগন্যাল অথবা ISP-র লাইনে সমস্যার ইঙ্গিত দেয়।", "warn")
                shortIssues.add("ইন্টারনেটে লস/হাই জিটার (Loss $iLoss%, Jitter ${iJitter}ms)")
            }

            if (!dnsRes.optBoolean("ok", false)) {
                addFinding("DNS রেসপন্স সমস্যা", "ডোমেইন নেম (google.com) রিজলভ করা যায়নি।", "warn")
                shortIssues.add("DNS কাজ করছে না")
            } else if (dnsRes.optDouble("ms", 0.0) > 120) {
                val dnsMs = dnsRes.optDouble("ms")
                addFinding("DNS ধীরগতি", "DNS রেসপন্স অনেক ধীর ($dnsMs ms)। ওয়েবসাইট খুলতে দেরি হতে পারে (রাউটারে 1.1.1.1 বা 8.8.8.8 DNS সেট করলে দ্রুত হতে পারে)।", "warn")
                shortIssues.add("DNS স্লো (${dnsMs}ms)")
            }

            // ৪. ব্যান্ডউইথের চাপ
            val periodBn = timeInfo.getString("period_bn")
            val isPeak = timeInfo.getBoolean("is_peak")
            if (!inetOk) {
                bwText = "ইন্টারনেট বিচ্ছিন্ন (ব্যান্ডউইথ পাওয়া যাচ্ছে না)"
                bwLevel = "danger"
            } else if (!dlRes.optBoolean("ok", false) || !ulRes.optBoolean("ok", false)) {
                bwText = "স্পিড টেস্ট ব্যর্থ (সংযোগ অস্থির)"
                bwLevel = "warn"
            } else {
                val dl = dlRes.getDouble("mbps")
                val ul = ulRes.getDouble("mbps")
                if (pkgMbps != null && pkgMbps > 0) {
                    val dlPct = (dl / pkgMbps) * 100.0
                    val ulPct = (ul / pkgMbps) * 100.0
                    val pkgStr = if (pkgMbps % 1.0 == 0.0) pkgMbps.toInt().toString() else pkgMbps.toString()
                    if (dlPct < 50) {
                        bwText = "উচ্চ চাপ — প্যাকেজের তুলনায় স্পিড অনেক কম ($dl/$pkgStr Mbps, ${dlPct.roundToInt()}%)"
                        bwLevel = "danger"
                        addFinding("ব্যান্ডউইথে উচ্চ চাপ (স্পিড ঘাটতি)", "প্যাকেজ $pkgStr Mbps-এর বিপরীতে ডাউনলোড পাওয়া গেছে $dl Mbps (${dlPct.roundToInt()}%)। $periodBn-এ ISP ব্যান্ডউইথ কনজেশন বা লিমিট থাকতে পারে।", "danger")
                        shortIssues.add("ব্যান্ডউইথে উচ্চ চাপ (DL $dl/$pkgStr Mbps)")
                    } else if (dlPct < 75 || ulPct < 60) {
                        bwText = "মাঝারি চাপ — $periodBn-এ স্পিড কিছুটা কম ($dl/$pkgStr Mbps, ${dlPct.roundToInt()}%)"
                        bwLevel = "warn"
                        addFinding("ব্যান্ডউইথে মাঝারি চাপ", "প্যাকেজ $pkgStr Mbps-এর বিপরীতে ডাউনলোড $dl Mbps (${dlPct.roundToInt()}%) ও আপলোড $ul Mbps (${ulPct.roundToInt()}%) পাওয়া গেছে।", "warn")
                        shortIssues.add("ব্যান্ডউইথে মাঝারি চাপ (DL $dl/$pkgStr Mbps)")
                    } else {
                        bwText = "স্বাভাবিক — প্যাকেজ অনুযায়ী ভালো স্পিড (DL $dl / UL $ul Mbps)"
                        bwLevel = "ok"
                    }
                } else {
                    if (dl < 15 && ul > dl * 2) {
                        bwText = "উচ্চ ডাউনলোড চাপ — $periodBn-এ কনজেশন (DL $dl বনাম UL $ul Mbps)"
                        bwLevel = "warn"
                        addFinding("ব্যান্ডউইথে উচ্চ ডাউনলোড চাপ", "আপলোড ($ul Mbps) ভালো থাকলেও ডাউনলোড ($dl Mbps) অর্ধেকেরও কম। $periodBn-এ ISP ডাউনলোড ব্যান্ডউইথে অতিরিক্ত চাপ রয়েছে।", "warn")
                        shortIssues.add("ব্যান্ডউইথে ডাউনলোড চাপ বেশি (DL $dl / UL $ul Mbps)")
                    } else if (dl < 5) {
                        bwText = "উচ্চ চাপ — ডাউনলোড স্পিড খুবই কম ($dl Mbps)"
                        bwLevel = "warn"
                        addFinding("ব্যান্ডউইথ স্পিড কম", "ডাউনলোড স্পিড মাত্র $dl Mbps পাওয়া গেছে। ব্যান্ডউইথ কনজেশন থাকতে পারে।", "warn")
                        shortIssues.add("ডাউনলোড স্পিড খুব কম ($dl Mbps)")
                    } else if (isPeak && (iLoss > 0 || iJitter > 15)) {
                        bwText = "মাঝারি চাপ — পিক আওয়ারে নেটওয়ার্ক লোড (DL $dl / UL $ul Mbps)"
                        bwLevel = "warn"
                    } else {
                        bwText = "স্বাভাবিক ও চাপমুক্ত (DL $dl / UL $ul Mbps)"
                        bwLevel = "ok"
                    }
                }
            }

            if (findings.length() == 0) {
                addFinding("সবকিছু স্বাভাবিক আছে", "রাউটার, লোকাল লাইন, পিং, জিটার ও ব্যান্ডউইথ সন্তোষজনক অবস্থায় আছে।", "ok")
                overallStatus = "ভালো (Normal)"
                overallLevel = "ok"
            } else if (!inetOk || !rOk) {
                overallStatus = "গুরুতর সমস্যা (Critical)"
                overallLevel = "danger"
            } else {
                overallStatus = "সমস্যা শনাক্ত হয়েছে (Attention Needed)"
                overallLevel = "warn"
            }
        }

        val dlTxt = if (dlRes.optBoolean("ok", false)) "${dlRes.getDouble("mbps")} Mbps" else "ব্যর্থ"
        val ulTxt = if (ulRes.optBoolean("ok", false)) "${ulRes.getDouble("mbps")} Mbps" else "ব্যর্থ"
        val pkgTxt = if (pkgMbps != null) {
            val ps = if (pkgMbps % 1.0 == 0.0) pkgMbps.toInt().toString() else pkgMbps.toString()
            " (প্যাকেজ: $ps Mbps)"
        } else ""
        val pingTxt = if (inetOk) "${pInet.getDouble("avg")}ms / Jitter ${pInet.getDouble("jitter")}ms / Loss ${pInet.getDouble("loss")}%" else "সংযোগ নেই"
        val rPingTxt = if (pRouter.optBoolean("ok", false)) "${pRouter.getDouble("avg")}ms (Jitter ${pRouter.getDouble("jitter")}ms)" else "ব্যর্থ"
        val issueSummary = if (shortIssues.isNotEmpty()) shortIssues.joinToString("; ") else "কোনো বড় সমস্যা পাওয়া যায়নি"

        val shortReport = buildString {
            appendLine("📡 $APP_NAME রিপোর্ট (${timeInfo.getString("short_time")} BST)")
            appendLine("• অবস্থা: $overallStatus")
            appendLine("• ISP/ব্যান্ডউইথ: ${ispInfo.getString("display")}")
            appendLine("• রাউটার IP: ${hop1 ?: "N/A"} | লোকাল IP: ${localIp ?: "N/A"}")
            appendLine("• সময়কাল: ${timeInfo.getString("period_bn")}")
            appendLine("• স্পিড$pkgTxt: ডাউনলোড $dlTxt | আপলোড $ulTxt")
            appendLine("• রাউটার পিং: $rPingTxt")
            appendLine("• ইন্টারনেট পিং: $pingTxt")
            appendLine("• রাউটারের অবস্থা: $routerText")
            appendLine("• লোকাল লাইনের চাপ (ONU/কেবল): $localText")
            appendLine("• ব্যান্ডউইথের চাপ: $bwText")
            append("• মূল সমস্যা/পরামর্শ: $issueSummary")
        }

        val layerHealth = JSONObject().apply {
            put("router", JSONObject().put("text", routerText).put("level", routerLevel))
            put("local_line", JSONObject().put("text", localText).put("level", localLevel))
            put("bandwidth", JSONObject().put("text", bwText).put("level", bwLevel))
        }

        return JSONObject().apply {
            put("time_info", timeInfo)
            put("isp_info", ispInfo)
            put("local_ip", localIp ?: "পাওয়া যায়নি")
            put("hop1", hop1 ?: "পাওয়া যায়নি")
            put("hop2", hop2 ?: "পাওয়া যায়নি")
            put("hop3", hop3 ?: "N/A")
            put("pings", JSONArray().put(pRouter).put(pHop2).put(pInet))
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
