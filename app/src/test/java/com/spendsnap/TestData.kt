package com.spendsnap

import com.spendsnap.domain.ExtractionMethod
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus

/** Synthetic OCR/accessibility text in src/test/resources/fixtures. Replace with real (redacted) dumps after Phase 0. */
fun fixture(name: String): List<String> =
    requireNotNull(object {}.javaClass.classLoader.getResource("fixtures/$name.txt")) { "missing fixture $name" }
        .readText().lines()

const val T0 = 1_760_000_000_000L
const val MINUTE = 60_000L

fun tx(
    id: Long = 0,
    paise: Long? = 100,
    at: Long = T0,
    status: VerificationStatus = VerificationStatus.SUCCESS_REPORTED,
    method: ExtractionMethod = ExtractionMethod.ACCESSIBILITY,
    app: PaymentApp = PaymentApp.GOOGLE_PAY,
    ref: String? = null,
    description: String? = "Testing",
    category: String? = null,
    issues: Set<ReviewIssue> = emptySet(),
    duplicateOf: Long? = null,
) = Transaction(
    id = id,
    description = description,
    amountPaise = paise,
    paymentApp = app,
    timestamp = at,
    verificationStatus = status,
    extractionMethod = method,
    transactionReference = ref,
    category = category,
    reviewIssues = issues,
    possibleDuplicateOfId = duplicateOf,
)
