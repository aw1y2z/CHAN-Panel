package io.github.aw1y2z.changan.data

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocketFactory

/**
 * mqtt 协议车型的车况通道。
 *
 * 这类车型的 REST 车况接口（`get-car-condition-map` / `car/data`）都返回空对象，
 * 车况只在 MQTT 上推送，所以需要单独连一次 broker 主动查询。
 *
 * 流程：
 *  1. `getAuthTokenByUserId?uid=` 换 MQTT 登录口令；
 *  2. `getConnConf` 拿 broker 地址与 topic 规则（同时从 topic 里解析车辆 tuid）；
 *  3. 连 broker，发 `loginout/req`，从响应拿本次会话的 `secretKey`；
 *  4. 发 `properties/get/req`（`sers` 加密），收 `properties/get/res`；
 *  5. 用 `MD5(r)` 作为 IV 解出车况 JSON。
 */
object MqttCarStatus {

    private const val TAG = "MqttCarStatus"
    private const val CLIENT_SUFFIX = "_watchCtrl"

    /** 本次会话的 secretKey，登录响应里下发 */
    @Volatile
    private var sessionSecret = ""

    /**
     * 查询车况。阻塞调用，需在工作线程执行；失败返回 null。
     *
     * @param context 用于读取 android_id —— `getConnConf` 的 deviceId 必须是它
     */
    fun fetchStatus(carId: String, context: android.content.Context): JSONObject? {
        val uid = TokenStore.userId
        if (uid.isEmpty()) {
            Log.w(TAG, "缺少 open 平台 uid")
            return null
        }
        sessionSecret = ""

        val authToken = fetchAuthToken(uid) ?: run {
            Log.w(TAG, "获取 MQTT 口令失败")
            return null
        }
        val conf = fetchConnConf(carId, uid, context) ?: run {
            Log.w(TAG, "获取 MQTT 连接配置失败")
            return null
        }
        val tuid = conf.first
        val hostPort = conf.second
        if (tuid.isEmpty() || hostPort.isEmpty()) {
            Log.w(TAG, "配置缺少 tuid 或 broker")
            return null
        }

        // 必须用 ssl:// 前缀，否则 Paho 会因 SocketFactory 与 URI 协议不匹配而拒绝
        val client = MqttClient("ssl://$hostPort", clientId(uid), null)
        val result = JSONObject()
        val done = CountDownLatch(2)   // 登录响应 + 车况响应

        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                Log.w(TAG, "连接断开: ${cause?.message}")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload, Charsets.UTF_8)
                Log.i(TAG, "recv $topic ${payload.take(160)}")
                val root = runCatching { JSONObject(payload) }.getOrNull() ?: return

                if (topic.endsWith("/loginout/res")) {
                    val arr = root.optJSONArray("rs") ?: return
                    for (i in 0 until arr.length()) {
                        val p = arr.optJSONObject(i)?.optJSONObject("params") ?: continue
                        val key = p.optString("secretKey")
                        if (key.isNotEmpty()) {
                            sessionSecret = key
                            Log.i(TAG, "拿到 secretKey")
                            // 同步 MqttClient 不能在回调线程里 publish，另起线程避免阻塞
                            Thread {
                                runCatching { publishPropertyGet(client, tuid) }
                                    .onFailure { Log.w(TAG, "发车况请求失败: ${it.message}") }
                            }.start()
                            done.countDown()
                            return
                        }
                    }
                    return
                }

                if (topic.endsWith("/get/res") || topic.endsWith("/report")) {
                    val rs = root.optString("rs")
                    val r = root.optString("r")
                    if (rs.isEmpty() || r.isEmpty() || sessionSecret.isEmpty()) return
                    val plain = runCatching { decrypt(sessionSecret, r, rs) }.getOrNull() ?: run {
                        Log.w(TAG, "车况解密失败")
                        return
                    }
                    Log.i(TAG, "车况明文 ${plain.length} 字符: ${plain.take(400)}")
                    parseStatus(plain, result)
                    Log.i(TAG, "解析出 ${result.length()} 个字段")
                    done.countDown()
                }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        return try {
            val opts = MqttConnectOptions().apply {
                userName = uid
                password = authToken.toCharArray()
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 60
                socketFactory = SSLSocketFactory.getDefault()
            }
            client.connect(opts)
            Log.i(TAG, "MQTT 已连接 $hostPort")
            client.subscribe("\$vdp/$uid/loginout/res", 1)
            // 不能用 # 通配：服务端 ACL 只放行具体主题，通配会返回 granted=128
            client.subscribe("\$vdp/$tuid/properties/get/res", 1)
            client.subscribe("\$vdp/$tuid/properties/report", 1)
            client.subscribe("\$vdp/$tuid/properties/set/res", 1)
            publishLogin(client, uid)

            done.await(25, TimeUnit.SECONDS)
            if (result.length() > 0) result else null
        } catch (e: Exception) {
            val rc = (e as? org.eclipse.paho.client.mqttv3.MqttException)?.reasonCode
            Log.w(TAG, "MQTT 失败: ${e.message} reasonCode=$rc cause=${e.cause?.message}")
            null
        } finally {
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
    }

    // ---------- 步骤 1/2：口令与配置 ----------

    private fun fetchAuthToken(uid: String): String? = runCatching {
        val resp = ChanganClient.getVcs(
            "/openc-apigw/vot-connect-auth-center/api/v1/auth/getAuthTokenByUserId",
            mapOf("uid" to uid),
        )
        JSONObject(resp).optJSONObject("data")?.optString("authToken").orEmpty()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** @return (车辆 tuid, broker host:port) */
    private fun fetchConnConf(
        carId: String,
        uid: String,
        context: android.content.Context,
    ): Pair<String, String>? = runCatching {
        val body = JSONObject()
            .put("tuid", uid)
            .put("carId", carId)
            .put("deviceId", deviceId(context))
            .put("deviceType", "1")
            .toString()
        val resp = ChanganClient.postJsonVcs(
            "/openc-apigw/vot-connect-conf-center/api/v1/device/getConnConf", body,
        )
        val data = JSONObject(resp).optJSONObject("data") ?: return null
        val info = data.optJSONArray("mqttConnectionInfos")?.optJSONObject(0) ?: return null
        val cluster = info.optJSONArray("clusterInfos")?.optJSONObject(0) ?: return null
        val broker = cluster.optString("brokerUrl")
            .removePrefix("ssl://").removePrefix("tcp://").trimEnd('/')
        val hostPort = if (broker.contains(":")) broker else "$broker:8883"

        var tuid = ""
        val topics = info.optJSONArray("topicInfos")
        if (topics != null) {
            outer@ for (i in 0 until topics.length()) {
                val pubs = topics.optJSONObject(i)?.optJSONArray("pubTopics") ?: continue
                for (k in 0 until pubs.length()) {
                    val m = Regex("\\\$vdp/([^/]+)/").find(pubs.optString(k))
                    if (m != null && !m.groupValues[1].equals(uid, true)) {
                        tuid = m.groupValues[1]
                        break@outer
                    }
                }
            }
        }
        tuid to hostPort
    }.getOrNull()

    // ---------- 步骤 3/4：发请求 ----------

    private fun publishLogin(client: MqttClient, uid: String) {
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("did", uid)
            .put("r", "${uid}_${now * 1000}")
            .put("v", "v1.0.0")
            .put("mt", "loginout")
            .put("z", "unzip")
            .put("a", 0)
            .put("e", 0)
            .put("tf", 0)
            .put("dt", now)
            .put(
                "sers",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("service_code", "login")
                        .put(
                            "params",
                            JSONObject()
                                .put("encryptEnable", 1)
                                .put("zipType", "gzip")
                                .put("ts", now),
                        ),
                ),
            )
        client.publish("\$vdp/$uid/loginout/req", body.toString().toByteArray(), 1, false)
    }

    private fun publishPropertyGet(client: MqttClient, tuid: String) {
        if (sessionSecret.isEmpty()) return
        val now = System.currentTimeMillis()
        val r = "${tuid}_${now * 1000}"
        val body = JSONObject()
            .put("did", tuid)
            .put("dt", now)
            .put("e", 1)
            .put("mt", "properties")
            .put("r", r)
            .put("sers", encrypt(sessionSecret, r, """[{"params":{},"service_code":"car_condition"}]"""))
            .put("tf", 0)
            .put("v", "1.0.0")
            .put("z", "gzip")
        client.publish("\$vdp/$tuid/properties/get/req", body.toString().toByteArray(), 1, false)
    }

    // ---------- 加解密 ----------

    private fun ivOf(r: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(r.toByteArray())

    private fun encrypt(secret: String, r: String, plain: String): String {
        val b64 = android.util.Base64.encodeToString(gzip(plain.toByteArray()), android.util.Base64.NO_WRAP)
        val data = b64.toByteArray()
        val pad = 16 - data.size % 16
        val padded = data + ByteArray(pad) { pad.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.toByteArray(), "AES"), IvParameterSpec(ivOf(r)))
        return android.util.Base64.encodeToString(cipher.doFinal(padded), android.util.Base64.NO_WRAP)
    }

    private fun decrypt(secret: String, r: String, b64: String): String {
        val ct = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.toByteArray(), "AES"), IvParameterSpec(ivOf(r)))
        val inner = String(cipher.doFinal(ct), Charsets.UTF_8).trim()
        return String(gunzip(android.util.Base64.decode(inner, android.util.Base64.DEFAULT)), Charsets.UTF_8)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(data.inputStream()).use { input ->
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    // ---------- 解析与设备标识 ----------

    private fun parseStatus(plain: String, target: JSONObject) {
        val arr = runCatching { org.json.JSONArray(plain) }.getOrNull() ?: return
        if (arr.length() == 0) return
        val params = arr.optJSONObject(0)?.optJSONObject("params") ?: return
        val keys = params.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            target.put(k, params.opt(k))
        }
    }

    /** 必须用系统 android_id：服务端按它校验设备 */
    private fun deviceId(context: android.content.Context): String = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        )
    }.getOrDefault("")

    private fun clientId(uid: String): String {
        val uuid = TokenStore.mqttDeviceUuid.ifEmpty {
            java.util.UUID.randomUUID().toString().also { TokenStore.mqttDeviceUuid = it }
        }
        return uid + "_" + uuid + CLIENT_SUFFIX
    }
}
