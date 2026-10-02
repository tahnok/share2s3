package io.github.tahnok.share2s3.s3

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Uses the worked examples from the AWS documentation:
 * https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
 */
class SigV4SignerTest {
    private val signer = SigV4Signer(
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region = "us-east-1",
    )
    private val time = Instant.parse("2013-05-24T00:00:00Z")

    @Test
    fun `GET object example`() {
        val auth = signer.authorization(
            method = "GET",
            canonicalUri = "/test.txt",
            query = emptyMap(),
            headers = mapOf(
                "Host" to "examplebucket.s3.amazonaws.com",
                "Range" to "bytes=0-9",
                "x-amz-content-sha256" to SigV4Signer.EMPTY_SHA256,
                "x-amz-date" to "20130524T000000Z",
            ),
            payloadHash = SigV4Signer.EMPTY_SHA256,
            time = time,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            auth,
        )
    }

    @Test
    fun `PUT object example`() {
        val payload = "Welcome to Amazon S3."
        val payloadHash = SigV4Signer.sha256Hex(payload.toByteArray())
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payloadHash)

        val auth = signer.authorization(
            method = "PUT",
            canonicalUri = "/" + SigV4Signer.uriEncode("test\$file.text"),
            query = emptyMap(),
            headers = mapOf(
                "Host" to "examplebucket.s3.amazonaws.com",
                "Date" to "Fri, 24 May 2013 00:00:00 GMT",
                "x-amz-date" to "20130524T000000Z",
                "x-amz-storage-class" to "REDUCED_REDUNDANCY",
                "x-amz-content-sha256" to payloadHash,
            ),
            payloadHash = payloadHash,
            time = time,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, " +
                "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            auth,
        )
    }

    @Test
    fun `GET bucket lifecycle example uses query string`() {
        val auth = signer.authorization(
            method = "GET",
            canonicalUri = "/",
            query = mapOf("lifecycle" to ""),
            headers = mapOf(
                "Host" to "examplebucket.s3.amazonaws.com",
                "x-amz-date" to "20130524T000000Z",
                "x-amz-content-sha256" to SigV4Signer.EMPTY_SHA256,
            ),
            payloadHash = SigV4Signer.EMPTY_SHA256,
            time = time,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
            auth,
        )
    }

    @Test
    fun `uriEncode escapes everything but unreserved characters`() {
        assertEquals("AZaz09-_.~", SigV4Signer.uriEncode("AZaz09-_.~"))
        assertEquals("a%20b%2Fc%24%2B", SigV4Signer.uriEncode("a b/c$+"))
        assertEquals("a%20b/c", SigV4Signer.uriEncode("a b/c", encodeSlash = false))
        assertEquals("%C3%A9t%C3%A9", SigV4Signer.uriEncode("été"))
    }
}
