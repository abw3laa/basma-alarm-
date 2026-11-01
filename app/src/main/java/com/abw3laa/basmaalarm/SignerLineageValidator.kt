package com.abw3laa.basmaalarm

/** Accepts exact signer equality, or a verified single-signer Android signing lineage. */
internal object SignerLineageValidator {
    fun accepts(
        installedCurrent: List<ByteArray>,
        candidateCurrent: List<ByteArray>,
        candidateHistory: List<ByteArray>,
        candidateHasPastCertificates: Boolean,
    ): Boolean {
        if (installedCurrent.isEmpty() || candidateCurrent.isEmpty()) return false
        if (sameSet(installedCurrent, candidateCurrent)) return true
        if (installedCurrent.size != 1 || candidateCurrent.size != 1 || !candidateHasPastCertificates) return false
        return candidateHistory.any { it.contentEquals(installedCurrent.single()) }
    }

    private fun sameSet(left: List<ByteArray>, right: List<ByteArray>): Boolean =
        left.size == right.size && left.all { a -> right.any { b -> a.contentEquals(b) } }
}
