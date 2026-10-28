package com.spendsnap.domain

import kotlin.math.abs

sealed interface DuplicateDecision {
    data object New : DuplicateDecision
    /** Same labeled transaction reference: definitely the same payment. */
    data class SameTransaction(val existing: Transaction) : DuplicateDecision
    /** One unambiguous match from a different source (screen vs notification). */
    data class Corroborates(val existing: Transaction) : DuplicateDecision
    /** Looks similar but cannot be proven; the user decides. */
    data class PossibleDuplicate(val existing: Transaction) : DuplicateDecision
}

/**
 * Conservative duplicate rules. Equal amounts alone never merge two entries:
 * a merge needs a matching reference, or a single cross-source match inside a short window.
 */
object DuplicateDetector {
    const val WINDOW_MILLIS = 5 * 60_000L

    fun decide(candidate: Transaction, sameReference: Transaction?, nearby: List<Transaction>): DuplicateDecision {
        if (sameReference != null && candidate.transactionReference != null) {
            return DuplicateDecision.SameTransaction(sameReference)
        }
        val amount = candidate.amountPaise ?: return DuplicateDecision.New
        val matches = nearby.filter {
            it.id != candidate.id &&
                it.extractionMethod != ExtractionMethod.MANUAL &&
                it.paymentApp == candidate.paymentApp &&
                it.direction == candidate.direction &&
                it.amountPaise == amount &&
                abs(it.timestamp - candidate.timestamp) <= WINDOW_MILLIS &&
                !referencesDiffer(it, candidate) &&
                statusesCompatible(it.verificationStatus, candidate.verificationStatus)
        }
        if (matches.isEmpty()) return DuplicateDecision.New
        val single = matches.singleOrNull()
        if (single != null &&
            single.extractionMethod != candidate.extractionMethod &&
            candidate.extractionMethod != ExtractionMethod.OCR &&
            single.verificationStatus != VerificationStatus.SUCCESS_CORROBORATED &&
            single.possibleDuplicateOfId == null
        ) {
            return DuplicateDecision.Corroborates(single)
        }
        // Same source, same amount is normal (two ₹1 payments in a row). Only an OCR re-import of
        // the same screenshot is flagged, and even then the user decides.
        val sameSource = matches.filter { it.extractionMethod == candidate.extractionMethod }
        if (candidate.extractionMethod == ExtractionMethod.OCR && sameSource.isNotEmpty()) {
            return DuplicateDecision.PossibleDuplicate(sameSource.maxBy { it.timestamp })
        }
        val crossSource = matches - sameSource.toSet()
        return if (crossSource.size > 1) DuplicateDecision.PossibleDuplicate(crossSource.maxBy { it.timestamp }) else DuplicateDecision.New
    }

    const val LIKELY_DUPLICATE_WINDOW_MILLIS = 24 * 60 * 60_000L

    /**
     * Pre-save check for entries the user is about to add (screenshot import, manual entry):
     * the same UPI reference, or the same amount and description within a day. Only a warning;
     * the user decides whether to save anyway.
     */
    fun findLikelyDuplicate(
        amountPaise: Long,
        description: String,
        reference: String?,
        at: Long,
        existing: List<Transaction>,
        direction: Direction = Direction.EXPENSE,
    ): Transaction? {
        reference?.let { ref -> existing.firstOrNull { it.transactionReference == ref }?.let { return it } }
        return existing
            .filter {
                it.amountPaise == amountPaise &&
                    it.direction == direction &&
                    it.verificationStatus != VerificationStatus.FAILED &&
                    it.description?.trim().equals(description.trim(), ignoreCase = true) &&
                    abs(it.timestamp - at) <= LIKELY_DUPLICATE_WINDOW_MILLIS
            }
            .maxByOrNull { it.timestamp }
    }

    /** Folds a new observation of the same payment into the existing entry. */
    fun merge(existing: Transaction, incoming: Transaction, independentSource: Boolean): Transaction {
        val status = combineStatus(existing.verificationStatus, incoming.verificationStatus, independentSource)
        val description = existing.description ?: incoming.description
        val amount = existing.amountPaise ?: incoming.amountPaise
        var issues = existing.reviewIssues + incoming.reviewIssues
        if (description != null) issues = issues - ReviewIssue.DESCRIPTION_MISSING
        if (amount != null) issues = issues - setOf(ReviewIssue.AMOUNT_MISSING, ReviewIssue.AMOUNT_AMBIGUOUS)
        if (status.countsTowardTotals || status == VerificationStatus.FAILED) {
            issues = issues - setOf(ReviewIssue.STATUS_UNVERIFIED, ReviewIssue.NO_PAYMENT_FLOW_SEEN)
        }
        if (status == VerificationStatus.UNVERIFIED &&
            (existing.verificationStatus.isSuccess || incoming.verificationStatus.isSuccess)
        ) {
            issues = issues + ReviewIssue.STATUS_CONFLICT
        }
        return existing.copy(
            description = description,
            amountPaise = amount,
            verificationStatus = status,
            transactionReference = existing.transactionReference ?: incoming.transactionReference,
            reviewIssues = issues,
        )
    }

    fun combineStatus(a: VerificationStatus, b: VerificationStatus, independentSource: Boolean): VerificationStatus {
        val failed = VerificationStatus.FAILED
        return when {
            a == VerificationStatus.USER_CONFIRMED || b == VerificationStatus.USER_CONFIRMED -> VerificationStatus.USER_CONFIRMED
            (a.isSuccess && b == failed) || (a == failed && b.isSuccess) -> VerificationStatus.UNVERIFIED
            a.isSuccess && b.isSuccess ->
                if (independentSource || a == VerificationStatus.SUCCESS_CORROBORATED || b == VerificationStatus.SUCCESS_CORROBORATED) {
                    VerificationStatus.SUCCESS_CORROBORATED
                } else {
                    VerificationStatus.SUCCESS_REPORTED
                }
            a.isSuccess || b.isSuccess -> VerificationStatus.SUCCESS_REPORTED
            a == failed || b == failed -> failed
            a == VerificationStatus.PENDING || b == VerificationStatus.PENDING -> VerificationStatus.PENDING
            else -> VerificationStatus.UNVERIFIED
        }
    }

    private fun referencesDiffer(a: Transaction, b: Transaction): Boolean =
        a.transactionReference != null && b.transactionReference != null && a.transactionReference != b.transactionReference

    /** A failure followed by a success of the same amount is usually a retry: a different payment. */
    private fun statusesCompatible(a: VerificationStatus, b: VerificationStatus): Boolean =
        !((a == VerificationStatus.FAILED && b.isSuccess) || (a.isSuccess && b == VerificationStatus.FAILED))
}
