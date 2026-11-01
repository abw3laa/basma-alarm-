package com.abw3laa.basmaalarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerLineageValidatorTest {
    private val oldSigner = byteArrayOf(1, 2, 3)
    private val newSigner = byteArrayOf(4, 5, 6)

    @Test fun acceptsUnchangedSingleOrMultiSignerSets() {
        assertTrue(SignerLineageValidator.accepts(listOf(oldSigner), listOf(oldSigner.copyOf()), listOf(oldSigner), false))
        assertTrue(SignerLineageValidator.accepts(listOf(oldSigner, newSigner), listOf(newSigner, oldSigner.copyOf()), emptyList(), false))
    }

    @Test fun acceptsOnlySingleSignerUpgradeWithVerifiedHistory() {
        assertTrue(SignerLineageValidator.accepts(listOf(oldSigner), listOf(newSigner), listOf(oldSigner, newSigner), true))
        assertFalse(SignerLineageValidator.accepts(listOf(oldSigner), listOf(newSigner), listOf(oldSigner, newSigner), false))
        assertFalse(SignerLineageValidator.accepts(listOf(oldSigner), listOf(newSigner), listOf(newSigner), true))
    }

    @Test fun rejectsMultiSignerChangesAndMissingCertificates() {
        assertFalse(SignerLineageValidator.accepts(listOf(oldSigner, newSigner), listOf(newSigner), listOf(oldSigner, newSigner), true))
        assertFalse(SignerLineageValidator.accepts(emptyList(), listOf(newSigner), listOf(oldSigner, newSigner), true))
        assertFalse(SignerLineageValidator.accepts(listOf(oldSigner), emptyList(), listOf(oldSigner), true))
    }
}
