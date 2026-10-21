package com.spendsnap.domain

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [Index("timestamp"), Index("transactionReference")],
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String?,
    /** Null until known: amounts are never guessed. */
    val amountPaise: Long?,
    val currency: String = "INR",
    val paymentApp: PaymentApp,
    /** Epoch millis when the payment was observed (or entered). */
    val timestamp: Long,
    val verificationStatus: VerificationStatus,
    val extractionMethod: ExtractionMethod,
    /** UPI reference / UTR, stored only when clearly labeled on screen. */
    val transactionReference: String? = null,
    val category: String? = null,
    val reviewIssues: Set<ReviewIssue> = emptySet(),
    val possibleDuplicateOfId: Long? = null,
)

val Transaction.needsReview: Boolean get() = reviewIssues.isNotEmpty()

/** Suspected duplicates and entries awaiting review stay out of totals until the user decides. */
val Transaction.countsTowardTotals: Boolean
    get() = verificationStatus.countsTowardTotals && amountPaise != null && possibleDuplicateOfId == null &&
        ReviewIssue.AWAITING_REVIEW !in reviewIssues

/** Shown with a "Verified" badge: counted and nothing left to check. */
val Transaction.isVerified: Boolean get() = countsTowardTotals && !needsReview

/** Shown as "Unverified": not counted yet or still needs a look. Failed payments are their own case. */
val Transaction.isUnverified: Boolean get() = !isVerified && verificationStatus != VerificationStatus.FAILED
