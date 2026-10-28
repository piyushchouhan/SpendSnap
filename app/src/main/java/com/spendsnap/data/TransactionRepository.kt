package com.spendsnap.data

import androidx.room.withTransaction
import com.spendsnap.domain.Direction
import com.spendsnap.domain.DuplicateDecision
import com.spendsnap.domain.DuplicateDetector
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus
import kotlinx.coroutines.flow.Flow

class TransactionRepository(private val db: AppDatabase) {
    private val dao = db.transactionDao()

    fun observeAll(): Flow<List<Transaction>> = dao.observeAll()
    fun observeReviewQueue(): Flow<List<Transaction>> = dao.observeReviewQueue()
    suspend fun get(id: Long): Transaction? = dao.get(id)
    suspend fun getAll(): List<Transaction> = dao.getAll()

    /** Entry point for automatic and OCR captures. Applies duplicate rules atomically. */
    suspend fun ingest(candidate: Transaction): Long = db.withTransaction {
        val sameReference = candidate.transactionReference?.let { dao.findByReference(it) }
        val nearby = if (candidate.amountPaise != null) {
            dao.findBetween(candidate.timestamp - DuplicateDetector.WINDOW_MILLIS, candidate.timestamp + DuplicateDetector.WINDOW_MILLIS)
        } else {
            emptyList()
        }
        when (val decision = DuplicateDetector.decide(candidate, sameReference, nearby)) {
            DuplicateDecision.New -> dao.insert(candidate)
            is DuplicateDecision.SameTransaction -> {
                val independent = decision.existing.extractionMethod != candidate.extractionMethod
                dao.update(DuplicateDetector.merge(decision.existing, candidate, independent))
                decision.existing.id
            }
            is DuplicateDecision.Corroborates -> {
                dao.update(DuplicateDetector.merge(decision.existing, candidate, independentSource = true))
                decision.existing.id
            }
            is DuplicateDecision.PossibleDuplicate -> dao.insert(
                candidate.copy(
                    possibleDuplicateOfId = decision.existing.id,
                    reviewIssues = candidate.reviewIssues + ReviewIssue.POSSIBLE_DUPLICATE,
                ),
            )
        }
    }

    suspend fun findLikelyDuplicate(
        amountPaise: Long,
        description: String,
        reference: String?,
        at: Long,
        direction: Direction = Direction.EXPENSE,
    ): Transaction? {
        val window = DuplicateDetector.LIKELY_DUPLICATE_WINDOW_MILLIS
        val candidates = dao.findBetween(at - window, at + window) + listOfNotNull(reference?.let { dao.findByReference(it) })
        return DuplicateDetector.findLikelyDuplicate(amountPaise, description, reference, at, candidates.distinctBy { it.id }, direction)
    }

    /** User edits and manual entries are saved as-is; no duplicate merging. */
    suspend fun save(transaction: Transaction): Long =
        if (transaction.id == 0L) dao.insert(transaction) else transaction.id.also { dao.update(transaction) }

    suspend fun delete(transaction: Transaction) = db.withTransaction {
        dao.delete(transaction)
        dao.clearDuplicateLinksTo(transaction.id)
    }

    /** Applies a later success/failure signal to an entry created when the user tapped Pay. */
    suspend fun applyOutcome(id: Long, status: VerificationStatus, note: String? = null) = db.withTransaction {
        val t = dao.get(id) ?: return@withTransaction
        if (t.verificationStatus == VerificationStatus.USER_CONFIRMED) return@withTransaction
        val resolved = status.countsTowardTotals || status == VerificationStatus.FAILED
        dao.update(
            t.copy(
                verificationStatus = DuplicateDetector.combineStatus(t.verificationStatus, status, independentSource = false),
                reviewIssues = if (resolved) t.reviewIssues - ReviewIssue.STATUS_UNVERIFIED else t.reviewIssues,
                description = note ?: t.description,
            ),
        )
    }

    /** Settings → Delete all data. Permanent: there is no backup. */
    suspend fun deleteAll() = dao.deleteAll()

    /** Undo for a delete: puts the entry back with its original id. */
    suspend fun restore(transaction: Transaction) {
        dao.insert(transaction)
    }

    suspend fun keepBoth(transaction: Transaction) = dao.update(
        transaction.copy(possibleDuplicateOfId = null, reviewIssues = transaction.reviewIssues - ReviewIssue.POSSIBLE_DUPLICATE),
    )
}
