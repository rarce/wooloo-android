package dev.herdroid

import androidx.test.platform.app.InstrumentationRegistry
import com.hierynomus.sshj.signature.SignatureEdDSA
import dev.herdroid.data.SecretStore
import net.schmizz.sshj.common.Buffer
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Shared by debug device tests and the isolated minified crypto smoke suite; never reads saved identities. */
class ReleaseCryptoTest {
    @Test
    fun generatedAndImportedEd25519KeysSignAndVerify() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.app
        val generated = app.keys.generateEd25519("release-smoke")
        var importedId: String? = null
        try {
            val pem = app.secrets.get(SecretStore.privateKey(generated.id))!!
            val imported = app.keys.import("release-smoke-import", pem, null)
            importedId = imported.id
            for (id in listOf(generated.id, imported.id)) {
                val key = app.keys.keyProvider(id)
                val message = "wooloo release Ed25519".toByteArray()
                val signer = SignatureEdDSA.Factory().create()
                signer.initSign(key.private)
                signer.update(message)
                val signature = signer.sign()
                // SSH envelopes carry the algorithm name and signature blob.
                val envelope = Buffer.PlainBuffer()
                    .putString("ssh-ed25519").putString(signature).compactData
                val verifier = SignatureEdDSA.Factory().create()
                verifier.initVerify(key.public)
                verifier.update(message)
                assertTrue(verifier.verify(envelope))
                val tampered = SignatureEdDSA.Factory().create()
                tampered.initVerify(key.public)
                tampered.update("different message".toByteArray())
                assertFalse(tampered.verify(envelope))
                // CVE-2020-36843: the removed EdDSA dependency accepted S + L.
                val order = java.math.BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed", 16)
                val scalar = java.math.BigInteger(1, signature.copyOfRange(32, 64).reversedArray())
                val nonCanonical = signature.copyOf()
                scalar.add(order).toByteArray().reversedArray().copyInto(nonCanonical, 32)
                val strict = SignatureEdDSA.Factory().create()
                strict.initVerify(key.public)
                strict.update(message)
                val malformed = Buffer.PlainBuffer().putString("ssh-ed25519").putString(nonCanonical).compactData
                assertFalse(strict.verify(malformed))
            }
        } finally {
            importedId?.let(app.keys::delete)
            app.keys.delete(generated.id)
        }
    }
}
