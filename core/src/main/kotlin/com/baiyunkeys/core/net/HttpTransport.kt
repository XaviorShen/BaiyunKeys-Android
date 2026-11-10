package com.baiyunkeys.core.net

/** 一次 HTTP 请求。core 不直接依赖具体客户端，方便测试注入假实现。 */
data class HttpRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 25_000,
)

data class HttpResponse(val code: Int, val body: String)

/** 传输层抽象。生产实现见 [OkHttpTransport]，测试用 `FakeTransport`。 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}
