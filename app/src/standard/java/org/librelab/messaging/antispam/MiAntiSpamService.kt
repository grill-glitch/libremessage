package org.librelab.messaging.antispam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real mi-anti-spam (MIUI/HyperOS yellow-page) number-marking lookup.
 * Port of the python client (mi-anti-spam) to Kotlin:
 *
 *   _encparam = Base64( AES-CBC-PKCS5Padding( params, key, IV="0102030405060708" ) )
 *   sign      = SHA1( appkey + "_encparam" + <_encparam> + secret ).upper()
 *   key       = GET /spbook/yellowpage/config/data -> data[3:-2] 再去掉 5~8 位
 *
 * Device identity (uuid + imeimd5 + oaId) is required — without it the
 * server degrades every lookup to the default 96110 record. It is generated
 * once and persisted in app-private prefs.
 *
 * Reverse-engineering source: com.miui.yellowpage + HyperOS miui.util.CoderUtils.
 * Educational use only — see the mi-anti-spam project's disclaimer.
 */
class MiAntiSpamService(private val context: Context) : AntiSpamService {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mi_antispam", Context.MODE_PRIVATE)

    override fun isAvailable(): Boolean = true

    override suspend fun lookup(number: String): NumberMark? = withContext(Dispatchers.IO) {
        runCatching {
            val device = loadDevice()
            val key = fetchEncryptKey(device)
            val result = queryNumber(number, key, device)
            parse(result)
        }.getOrNull()
    }

    // ---------------- device identity ----------------

    private fun loadDevice(): Device {
        val saved = prefs.getString("device", null)
        if (saved != null) {
            runCatching {
                val o = JSONObject(saved)
                val d = Device(o.getString("uuid"), o.getString("imeimd5"), o.getString("oaId"))
                if (d.uuid.isNotBlank() && d.oaId.isNotBlank()) return d
            }
        }
        val d = Device(UUID.randomUUID().toString(), "", randomHex(8))
        val d2 = d.copy(imeimd5 = md5(d.uuid))
        prefs.edit().putString("device", JSONObject().apply {
            put("uuid", d2.uuid)
            put("imeimd5", d2.imeimd5)
            put("oaId", d2.oaId)
        }.toString()).apply()
        return d2
    }

    private data class Device(val uuid: String, val imeimd5: String, val oaId: String)

    // ---------------- crypto ----------------

    private fun aesEncryptB64(plain: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(IV)
        )
        return android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray()), android.util.Base64.NO_WRAP)
    }

    private fun buildQuery(biz: Map<String, String>, key: String, device: Device): String {
        val params = LinkedHashMap<String, String>()
        // Common params + device identity (k0.e() injection).
        params["imeimd5"] = device.imeimd5
        params["lg"] = "zh_CN"
        params["region"] = "CN"
        params["sup"] = "mipay"
        params["uuid"] = device.uuid
        params["oaId"] = device.oaId
        params["apkVersion"] = "230260506"
        params["androidVersion"] = "15"
        params["v"] = "16"
        params.putAll(biz)
        val joined = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val enc = aesEncryptB64(joined, key)
        val sign = sha1(APPKEY + "_encparam" + enc + SECRET).uppercase()
        return "appkey=$APPKEY&sign=$sign&_encparam=${URLEncoder.encode(enc, "UTF-8")}"
    }

    // ---------------- HTTP ----------------

    private fun httpGet(url: String, key: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 25_000
        conn.readTimeout = 25_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw RuntimeException("HTTP $code: ${err.take(300)}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchEncryptKey(device: Device): String {
        // GET /spbook/yellowpage/config/data -> data[3:-2] 再去掉 5~8 位 -> 16 字节密钥
        val url = "$API/spbook/yellowpage/config/data?${buildQuery(emptyMap(), "0".repeat(16), device)}"
        val outer = JSONObject(httpGet(url, "0".repeat(16)))
        if (outer.optInt("code") != 0) throw RuntimeException("config/data failed: ${outer.toString().take(200)}")
        val s = outer.getString("data").substring(3, outer.getString("data").length - 2)
        return s.substring(0, 5) + s.substring(8)
    }

    private fun queryNumber(number: String, key: String, device: Device): JSONObject {
        // GET /spbook/yellowpage/query
        val params = mapOf(
            "phone" to number,
            "raw_phone" to number,
            "version_code" to "230260506",
            "india_normalize" to "v3",
            "show_india_provider" to "true",
            "app_type" to "yellowpage",
        )
        val url = "$API/spbook/yellowpage/query?${buildQuery(params, key, device)}"
        val outer = JSONObject(httpGet(url, key))
        if (outer.optInt("code") != 0) throw RuntimeException("query failed: ${outer.toString().take(200)}")
        val data = outer.opt("data")
        return when (data) {
            is String -> if (data.isNotBlank()) JSONObject(data) else JSONObject()
            is JSONObject -> data
            else -> JSONObject()
        }
    }

    // ---------------- parse ----------------

    private fun parse(result: JSONObject): NumberMark? {
        val atd = result.optJSONObject("atd")
        val yp = result.optJSONObject("yp")
        val risk = result.optJSONObject("phoneRiskInfo")

        val catId = atd?.optInt("catId") ?: 0
        val category = atd?.optString("catTitle")?.takeIf { it.isNotBlank() }
            ?: CATEGORIES[catId]
        val businessName = yp?.optString("sName")?.takeIf { it.isNotBlank() }
        val thumb = yp?.optString("thumbnail")?.takeIf { it.isNotBlank() }
        val iconUrl = thumb?.let {
            val domain = result.optString("image_domain").takeIf { d -> d.isNotBlank() } ?: "file.market.xiaomi.com"
            val base = if (domain.startsWith("http")) domain else "https://$domain"
            "$base/thumbnail/png/w200/$it"
        } ?: atd?.optString("cheatPhonePhoto")?.takeIf { it.isNotBlank() }

        val mark = NumberMark(
            category = category,
            categoryId = catId.takeIf { it > 0 },
            count = atd?.optInt("count")?.takeIf { it > 0 },
            businessName = businessName,
            iconUrl = iconUrl,
            riskType = risk?.optString("riskType")?.takeIf { it.isNotBlank() },
        )
        return mark.takeIf { it.isMarked }
    }

    private companion object {
        const val API = "https://api.huangye.miui.com"
        const val APPKEY = "yellowpage"
        const val SECRET = "77eb2e8a5755abd016c0d69ba74b219c"
        val IV = "0102030405060708".toByteArray()
        const val UA = "cupid/M2012K11AC; MIUI/V15.0.7.0.TLCCNXM E/V15.0.7 B/S L/zh-CN LO/CN"

        // cid -> zh_CN name (C0250j.java)
        val CATEGORIES = mapOf(
            1 to "高风险",
            2 to "房产中介",
            3 to "广告推销",
            5 to "快递外卖",
            6 to "贷款推销",
            13 to "教育培训",
            14 to "装修维修",
            21 to "保险理财",
        )

        fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }

        fun sha1(s: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }

        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            java.security.SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
