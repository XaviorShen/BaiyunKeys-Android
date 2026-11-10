package com.baiyunkeys.core.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** OkHttp 实现。连接池与超时按 iOS 侧 `URLSession` 的 25s 对齐。 */
class OkHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build(),
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(request.url)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-Hans-US;q=1, en-US;q=0.9")

        request.headers.forEach { (key, value) -> builder.header(key, value) }

        val body = request.body
        if (request.method == "GET") {
            builder.get()
        } else {
            val payload = body.orEmpty()
            builder.method(
                request.method,
                payload.toRequestBody(JSON_MEDIA_TYPE),
            )
        }

        client.newCall(builder.build()).execute().use { response ->
            HttpResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
