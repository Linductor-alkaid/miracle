package dev.linductor.miracle.host

import dev.linductor.miracle.host.HttpTransportBinding.EndpointPolicyCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 传输端点静态策略（SSRF 姿态：仅 https + 拒绝私有/回环/链路本地主机）。 */
class EndpointPolicyCheckTest {

    @Test
    fun `允许公网 https 端点`() {
        assertTrue(EndpointPolicyCheck.isAllowed("https://api.example.com/v1/chat"))
        assertTrue(EndpointPolicyCheck.isAllowed("https://dashscope.aliyuncs.com"))
        assertTrue(EndpointPolicyCheck.isAllowed("https://api.example.com.:8443/x"))
    }

    @Test
    fun `拒绝非 https`() {
        assertFalse(EndpointPolicyCheck.isAllowed("http://api.example.com"))
        assertFalse(EndpointPolicyCheck.isAllowed("ftp://api.example.com"))
        assertFalse(EndpointPolicyCheck.isAllowed("api.example.com"))
    }

    @Test
    fun `拒绝回环与本地域名`() {
        assertFalse(EndpointPolicyCheck.isAllowed("https://localhost/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://app.localhost/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://printer.local/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://service.internal/v1"))
    }

    @Test
    fun `拒绝私有与链路本地 IPv4`() {
        assertFalse(EndpointPolicyCheck.isAllowed("https://10.0.0.5/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://127.0.0.1/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://172.16.1.1/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://172.31.255.255/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://192.168.1.2/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://169.254.1.1/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://0.0.0.0/v1"))
    }

    @Test
    fun `公网 IPv4 与 IPv6 字面量策略`() {
        assertTrue(EndpointPolicyCheck.isAllowed("https://8.8.8.8/v1"))
        // IPv6 字面量一律拒绝（保守；v1 无需求）
        assertFalse(EndpointPolicyCheck.isAllowed("https://[2606:4700::1]/v1"))
        assertFalse(EndpointPolicyCheck.isAllowed("https://[::1]/v1"))
    }

    @Test
    fun `拒绝非法 URL`() {
        assertFalse(EndpointPolicyCheck.isAllowed("https://"))
        assertFalse(EndpointPolicyCheck.isAllowed(""))
        assertFalse(EndpointPolicyCheck.isAllowed("not a url"))
    }
}
