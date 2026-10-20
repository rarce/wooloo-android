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
            }
        } finally {
            importedId?.let(app.keys::delete)
            app.keys.delete(generated.id)
        }
    }
}
