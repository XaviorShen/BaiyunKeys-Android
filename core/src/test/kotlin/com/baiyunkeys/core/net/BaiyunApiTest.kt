package com.baiyunkeys.core.net

import com.baiyunkeys.core.crypto.Sm2Gm
import com.baiyunkeys.core.hex.Hex
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 三条通道的协议级测试：请求路径 / 头 / 体 + 返回映射，全程离线。 */
class BaiyunApiTest {

    private class FakeTransport : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var responder: (HttpRequest) -> HttpResponse = { HttpResponse(200, "[]") }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responder(request)
        }

        fun urlContains(fragment: String): HttpRequest =
            requests.first { it.url.contains(fragment) }
    }

    private fun rsaPem(): String {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val encoded = Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded)
        return buildString {
            appendLine("-----BEGIN PUBLIC KEY-----")
            encoded.chunked(64).forEach { appendLine(it) }
            append("-----END PUBLIC KEY-----")
        }
    }

    private fun api(transport: HttpTransport) = BaiyunApi(transport)

    // MARK: - check_state

    @Test
    fun checkStateReturnsTokenAndSendsExpectedBody() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, """[{"access_token":"TOKEN-1"}]""") }

        val token = api(transport).checkState(" openid-abc ")

        assertEquals("TOKEN-1", token)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/mini_program/api_01/check_state"))
        assertTrue(request.url.startsWith(BaiyunApi.DEFAULT_BASE_XCX))
        assertEquals("""{"openId":"openid-abc","oper_type":"INDEX"}""", request.body)
    }

    @Test
    fun checkStateFailureCarriesServerMessage() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, """[{"resp_msg":"OpenID 已过期"}]""") }

        val error = runCatching { api(transport).checkState("x") }.exceptionOrNull()
        assertTrue(error is BaiyunException)
        assertEquals("OpenID 已过期", error?.message)
    }

    // MARK: - 通道二：OpenID 明文

    @Test
    fun guardsByOpenIdWalksThreeStepsAndMapsFields() = runTest {
        val transport = FakeTransport()
        transport.responder = { request ->
            when {
                request.url.contains("check_state") ->
                    HttpResponse(200, """[{"access_token":"AT"}]""")

                request.url.contains("get_rsa_public_key") ->
                    HttpResponse(200, """[{"result":${quote(rsaPem())}}]""")

                request.url.contains("get_guard_list_by_phone") ->
                    HttpResponse(
                        200,
                        """
                        [{"resp_code":"000000000000","data_list":[
                          {"address":"一号楼大门","macNum":"aabbccddeeff","productKey":"00112233445566778899AABBCCDDEEFF","bluetoothName":"by0aabbccdd"},
                          {"name":"二号楼","bluetoothName":"BY112233445","key":"A1B2C3D4E5F60718"},
                          {"doorName":"","mac":"11:22:33:44:55:66","productKey":""}
                        ]}]
                        """.trimIndent(),
                    )

                else -> HttpResponse(404, "")
            }
        }

        val doors = api(transport).guardsByOpenId("13800138000", "openid").map { it.asDoorConfig() }

        // 三条请求：换 token → 取公钥（GET）→ 拉列表
        assertEquals(3, transport.requests.size)
        assertEquals("GET", transport.requests[1].method)
        assertEquals("AT", transport.requests[1].headers["cloud_shield_token"])
        assertTrue("手机号要走 RSA 分块加密", transport.urlContains("get_guard_list_by_phone").body!!.contains("RSA_STRTOK@@@"))

        assertEquals(2, doors.size)
        assertEquals("一号楼大门", doors[0].name)
        assertEquals("AA:BB:CC:DD:EE:FF", doors[0].mac)
        assertEquals("00112233445566778899AABBCCDDEEFF", doors[0].key)
        assertEquals("BY0AABBCCDD", doors[0].bluetoothName)

        // 第二条没有 MAC，由蓝牙名反推
        assertEquals("二号楼", doors[1].name)
        assertEquals("00:01:12:23:34:45", doors[1].mac)
    }

    @Test
    fun guardListErrorCodeThrows() = runTest {
        val transport = FakeTransport()
        transport.responder = { request ->
            when {
                request.url.contains("check_state") -> HttpResponse(200, """[{"access_token":"AT"}]""")
                request.url.contains("get_rsa_public_key") ->
                    HttpResponse(200, """[{"result":${quote(rsaPem())}}]""")

                else -> HttpResponse(200, """[{"resp_code":"E1001","resp_msg":"手机号未授权"}]""")
            }
        }

        val error = runCatching { api(transport).guardsByOpenId("138", "o") }.exceptionOrNull()
        assertTrue(error is BaiyunException)
        assertEquals("手机号未授权", error?.message)
    }

    // MARK: - 通道三：加密下发

    @Test
    fun encryptedGuardsKeepsCipherText() = runTest {
        val cipher = "04" + "AB".repeat(111)
        val transport = FakeTransport()
        transport.responder = { request ->
            when {
                request.url.contains("get_rsa_public_key") ->
                    HttpResponse(200, """[{"result":${quote(rsaPem())}}]""")

                else -> HttpResponse(
                    200,
                    """[{"resp_code":"000000000000","data_list":[
                        {"address":"加密门禁","macNum":"aabbccddeeff","productKey":"$cipher","bluetoothName":"BY0AABBCCDD"}
                    ]}]""",
                )
            }
        }

        val items = api(transport).encryptedGuards("13800138000", "AT")
        assertEquals(1, items.size)
        assertEquals(cipher, items.single().encryptedKey)
    }

    @Test
    fun activationDecryptsCipherAndPassesThroughPlainKey() = runTest {
        val (privateKey, publicKey) = Sm2Gm.generateKeyPair()
        val secretKey = "00112233445566778899AABBCCDDEEFF"
        // 服务端加密的是 key 的原始字节，解出来就是这串 hex
        val cipher = Sm2Gm.encrypt(Hex.bytesFromHex(secretKey), publicKey)

        val transport = FakeTransport()
        transport.responder = { request ->
            when {
                request.url.contains("check_state") -> HttpResponse(200, """[{"access_token":"AT"}]""")
                request.url.contains("go_home_service_login") -> HttpResponse(200, """[{"resp_code":"000000000000"}]""")
                request.url.contains("get_rsa_public_key") ->
                    HttpResponse(200, """[{"result":${quote(rsaPem())}}]""")

                else -> HttpResponse(
                    200,
                    """[{"resp_code":"000000000000","data_list":[
                        {"address":"密文门禁","macNum":"aabbccddeeff","productKey":"$cipher"},
                        {"address":"明文门禁","macNum":"112233445566","productKey":"A1B2C3D4E5F60718"}
                    ]}]""",
                )
            }
        }

        val doors = Activation.fetchEncrypted(
            api = api(transport),
            phone = "13800138000",
            openId = "openid",
            smPublicKey = publicKey,
            smPrivateKey = privateKey,
        )

        assertEquals(2, doors.size)
        assertEquals(secretKey, doors[0].key)
        assertEquals("A1B2C3D4E5F60718", doors[1].key)
        assertEquals("AA:BB:CC:DD:EE:FF", doors[0].mac)
    }

    @Test
    fun activationRejectsBadSm2Keys() = runTest {
        val transport = FakeTransport()
        val api = api(transport)

        val badPublic = runCatching {
            Activation.fetchEncrypted(api, "138", "o", "04AB", "00".repeat(32))
        }.exceptionOrNull()
        assertTrue(badPublic is IllegalArgumentException)

        val badPrivate = runCatching {
            Activation.fetchEncrypted(api, "138", "o", "04" + "AB".repeat(64), "1234")
        }.exceptionOrNull()
        assertTrue(badPrivate is IllegalArgumentException)
    }

    @Test
    fun activationReportsAllDecryptionFailed() = runTest {
        val (_, publicKey) = Sm2Gm.generateKeyPair()
        val (otherPrivate, _) = Sm2Gm.generateKeyPair()
        val cipher = Sm2Gm.encrypt("secret".toByteArray(), publicKey)

        val transport = FakeTransport()
        transport.responder = { request ->
            when {
                request.url.contains("check_state") -> HttpResponse(200, """[{"access_token":"AT"}]""")
                request.url.contains("go_home_service_login") -> HttpResponse(200, """[{"resp_code":"000000000000"}]""")
                request.url.contains("get_rsa_public_key") ->
                    HttpResponse(200, """[{"result":${quote(rsaPem())}}]""")

                else -> HttpResponse(
                    200,
                    """[{"resp_code":"000000000000","data_list":[{"address":"x","macNum":"aabbccddeeff","productKey":"$cipher"}]}]""",
                )
            }
        }

        val error = runCatching {
            Activation.fetchEncrypted(api(transport), "138", "o", publicKey, otherPrivate)
        }.exceptionOrNull()
        assertTrue(error is BaiyunException)
        assertTrue(error!!.message!!.contains("解密失败"))
    }

    // MARK: - 老接口：身份证

    @Test
    fun loginSendsDeviceProfileAndValidatesCode() = runTest {
        val transport = FakeTransport()
        transport.responder = { request ->
            when {
                request.url.contains("login/v1") ->
                    HttpResponse(200, """{"state":true,"code":"0000","extension":"TK","obj":{"id":9527}}""")

                request.url.contains("getList") -> HttpResponse(200, """{"obj":{"data_list":[]}}""")
                else -> HttpResponse(200, "{}")
            }
        }

        val api = api(transport)
        val auth = api.login(
            phone = "13800138000",
            idCard = "110101199001011234",
            profile = DeviceProfile.android("15", "Pixel 8", "udid-1"),
        )

        assertEquals("TK", auth.token)
        assertEquals("9527", auth.loginUser)

        val body = transport.urlContains("login/v1").body!!
        assertTrue("appVersion 要硬编码 1.3.6", body.contains("\"appVersion\":\"1.3.6\""))
        assertTrue("os 默认发 0（与 iOS 一致）", body.contains("\"os\":0"))
        assertTrue(body.contains("\"idcardNo\":\"110101199001011234\""))
        assertNotNull(transport.requests.firstOrNull())
    }

    @Test
    fun loginFailureUsesServerMessage() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(200, """{"state":false,"code":"1001","msg":"验证码错误"}""") }

        val error = runCatching {
            api(transport).login("138", "110", DeviceProfile.android("15", "m", "u"))
        }.exceptionOrNull()
        assertEquals("验证码错误", error?.message)
    }

    @Test
    fun entranceGuardsCollectsNestedDataList() = runTest {
        val transport = FakeTransport()
        transport.responder = {
            HttpResponse(
                200,
                """{"state":true,"obj":{"data_list":[
                    {"address":"深层门禁","macNum":"aabbccddeeff","productKey":"00112233445566778899AABBCCDDEEFF"}
                ]}}""",
            )
        }

        val guards = api(transport).entranceGuards(
            BaiyunApi.Auth(token = "TK", loginUser = "1", phone = "138"),
        )

        assertEquals(1, guards.size)
        assertEquals("深层门禁", guards.single().name)
        assertEquals("TK", transport.requests.single().headers["TOKEN"])
        assertEquals("1", transport.requests.single().headers["LOGIN_USER"])
    }

    @Test
    fun httpErrorIsReported() = runTest {
        val transport = FakeTransport()
        transport.responder = { HttpResponse(503, "") }

        val error = runCatching { api(transport).checkState("o") }.exceptionOrNull()
        assertEquals("请求失败：HTTP 503", error?.message)
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
