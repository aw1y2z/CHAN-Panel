package io.github.aw1y2z.changan.data

import okhttp3.Request

/**
 * 请求签名器接口。
 *
 * 长安引力域国行版已知使用（来自深蓝国行抓包侧写 + 本地 H5 资源）：
 *  - JWT + 自定义签名三件套 header（深蓝侧写为 x-deepal-crypto-signature / -timestamp / -noncestr，
 *    引力域的确切 header 名待抓包确认）
 *  - 部分接口请求体整包 encStr 加密（算法在原生加密 dex 内，未还原）
 *
 * 具体算法必须通过「真机脱壳（爱加固 ijiami.dat）+ 抓包比对」还原后，
 * 实现本接口并注入 [ChanganClient]。
 */
interface RequestSigner {
    /**
     * @return 需要附加到请求上的签名 header（如 signature / timestamp / noncestr），
     *         无签名时返回空 map。
     */
    fun sign(request: Request, timestampMs: Long, nonce: String): Map<String, String>
}

/** 占位实现：不发任何签名 header。仅用于工程骨架编译通过与抓包期明文探测。 */
object NoOpSigner : RequestSigner {
    override fun sign(request: Request, timestampMs: Long, nonce: String): Map<String, String> = emptyMap()
}
