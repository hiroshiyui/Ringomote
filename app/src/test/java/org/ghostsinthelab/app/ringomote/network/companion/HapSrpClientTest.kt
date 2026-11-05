package org.ghostsinthelab.app.ringomote.network.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors generated with srptools 0.2.0, the library the reference client uses against real
 * Apple TVs: PIN "0042", a fixed client exponent and a server built from the same context.
 */
class HapSrpClientTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val salt = hex("0a1b2c3d4e5f60718293a4b5c6d7e8f9")
    private val a = ByteArray(32) { 0x1f }
    private val serverPublic = hex(
        "e6c39f88c7fe8509164c62579df78699417e8f2abc214d7238e7059091b8cc61437951b29b2bbcbca147ad6058a153f80952ec005c1bb795b62c72d400926b9d445299ee98bcb88167f53ece7960e79ff0fedf6d794bbe4e91c276342e45018f32315055d202e179c82a9171479348798d15e331cf0ea3062aff78a67cc52b8b89a5fe604ec66950a5e55898523cba0e58f10a37e1d380e560a2b5e170dddb2662be87e8ef906b35da08c96e9fdb1d0cc89ff7689c2ef5f53c9cc3334e8b9cb08a4ee363ab441e39f124e58d81ee5677aeb5d042746fa01f91237e95be2d3afa6ac8941feb2add1812d7ad551da52a72f9c318a88626caee95d57506d62a3d867727f39345db6b7b2d764db060d818e4cde9b8c9cb8dd6db7b944b66567e424bfc5bc0e613468e7418df47e298d82b32e73958e1976b3e7616aedf77edfbe59fa7529194996fd3d482dfd7495d87738d191267914f0bd4e104fb13aadfff483b966c4a3d58e6adcee1b6f296c7c6f26d6a870664b3a06ac2d155634e3518e9d0",
    )

    @Test
    fun matchesReferenceVectors() {
        val client = HapSrpClient("0042", a)
        assertEquals(
            "86c32d0620a6f919061d4d01e4d1b75b774076c648674f2ecdee61eecec11241de2f3f5e13ebe5b52cfa9b1deb7e92a926227c12287208ca7a3fc6cce5663e5b3ffe47d1f3efb593ec6a431b553e55b5c35b802b0895802865b853c1df786de430f28cd84c4191bfb9b7498706829ec0861c3834bce90f4b332c09d623dcac458bbcda40a8616966857f51ed67ad524df488fcd57983a164f710117cea42a38dd40cb95473fc67bb8f3c7ecfa11ccd743e8bc03f99c751fb8eea2567665e3a13b27081c6e49e7f20aa0737447fb60cf20d7b385be6bdabd69c542f20268382457339a64b30a4358a63fdb7d2cdee58dbf1f6707c98cd6256d8eb9ef71cd20df58caf7ae652310792ff0de4df5ba565851f394fc1e9488955430a1b80e4eb45e3eed17da90e3dc3fb06689aba2eb3d1f57afed71773cd0d4014fe4a3513128c1f600db3bc2ad7abcd6bc3c4731d882269a9db06cebf23f60ca78cdad816750b823ba739b35691a487fbd108f4424615113af895629a2a822528385e76591491bd",
            client.publicKey.toHex(),
        )
        val m1 = client.process(salt, serverPublic)
        assertEquals(
            "fdaf60a2e161ba61d33d5f55db58f9ba2bb24eda9ac827bdd4d32b5138d77ab1eec100f02ce22d2a5023327cf1160cb532f902755e46d26d2be07c286205174f",
            m1.toHex(),
        )
        assertEquals(
            "a2caf2fde51dfbf1a5ff7c14b45914ead4f6d3c76b65b9b683f8c4e56a058a5a8f1d1801e19ccaf8520ce4d7616df0d903c931f40a06138ff9a15c8bdcd14525",
            client.key.toHex(),
        )
        assertTrue(client.verifyServerProof(hex("842a91a8ad7023775b221a81e350a54d55d28af22fbd7753d42bafe5387654daa62207c666ee035489814938647f33a938a2440a276a28ff2e2af5558afa7e69")))
        assertFalse(client.verifyServerProof(ByteArray(64)))
    }

    /** A different PIN changes the proof, so a wrong PIN is caught at M4. */
    @Test
    fun wrongPinGivesDifferentProof() {
        val right = HapSrpClient("0042", a).process(salt, serverPublic)
        val wrong = HapSrpClient("0043", a).process(salt, serverPublic)
        assertFalse(right.contentEquals(wrong))
    }
}
