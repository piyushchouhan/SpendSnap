package com.spendsnap.domain

import androidx.room.ColumnInfo
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
    // Default must match MIGRATION_1_2 so Room's schema check passes on upgraded databases.
    @ColumnInfo(defaultValue = "EXPENSE") val direction: Direction = Direction.EXPENSE,
)

val Transaction.needsReview: Boolean get() = reviewIssues.isNotEmpty()

/** Confirmed enough to count: suspected duplicates and entries awaiting review wait for the user. */
private val Transaction.settled: Boolean
    get() = verificationStatus.countsTowardTotals && amountPaise != null && possibleDuplicateOfId == null &&
        ReviewIssue.AWAITING_REVIEW !in reviewIssues

/** Counts toward spending totals. Income never does. */
val Transaction.countsTowardTotals: Boolean get() = settled && direction == Direction.EXPENSE

/** Counts toward income totals. */
val Transaction.countsAsIncome: Boolean get() = settled && direction == Direction.INCOME

/** Shown with a "Verified" badge: counted and nothing left to check. */
val Transaction.isVerified: Boolean get() = settled && !needsReview

/** Shown as "Unverified": not counted yet or still needs a look. Failed payments are their own case. */
val Transaction.isUnverified: Boolean get() = !isVerified && verificationStatus != VerificationStatus.FAILED
