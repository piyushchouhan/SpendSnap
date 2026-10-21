package com.spendsnap.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CsvExporter {
    private val HEADER = listOf(
        "id", "date_time", "description", "amount_inr", "amount_paise", "currency", "payment_app",
        "verification_status", "extraction_method", "transaction_reference", "category", "review_issues",
    )
    private val DATE = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun toCsv(transactions: List<Transaction>, zone: ZoneId): String = buildString {
        appendLine(HEADER.joinToString(","))
        for (t in transactions) {
            val row = listOf(
                t.id.toString(),
                DATE.format(Instant.ofEpochMilli(t.timestamp).atZone(zone)),
                t.description.orEmpty(),
                t.amountPaise?.let(Inr::plain).orEmpty(),
                t.amountPaise?.toString().orEmpty(),
                t.currency,
                t.paymentApp.label,
                t.verificationStatus.name,
                t.extractionMethod.name,
                t.transactionReference.orEmpty(),
                t.category.orEmpty(),
                t.reviewIssues.joinToString(";") { it.name },
            )
            appendLine(row.joinToString(",") { escape(it) })
        }
    }

    fun escape(value: String): String {
        // Neutralise spreadsheet formula injection in free-text fields.
        val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
