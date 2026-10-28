package com.spendsnap.domain

enum class PaymentApp(val packageName: String?, val label: String) {
    // Package names verified on the Pixel 9 with `adb shell pm list packages`.
    GOOGLE_PAY("com.google.android.apps.nbu.paisa.user", "Google Pay"),
    PHONEPE("com.phonepe.app", "PhonePe"),
    /** Manual entries only: nothing to detect. */
    CASH(null, "Cash"),
    OTHER(null, "Other");

    companion object {
        fun fromPackage(packageName: String?): PaymentApp? =
            entries.firstOrNull { it.packageName != null && it.packageName == packageName }
    }
}

/** Money out (a payment) or money in (received / salary / refund). */
enum class Direction(val label: String) { EXPENSE("Expense"), INCOME("Income") }

/** What a single screen (or notification) appears to show. Screen text alone never proves money moved. */
enum class ScreenStatus { AMOUNT_ENTRY, PENDING, SUCCESS, FAILED, NOT_AN_EXPENSE, UNKNOWN }

enum class VerificationStatus(val label: String, val countsTowardTotals: Boolean) {
    UNVERIFIED("Unverified", false),
    PENDING("Pending", false),
    FAILED("Failed", false),
    /** One source (screen or notification) reported success. */
    SUCCESS_REPORTED("Success reported by app", true),
    /** Two independent sources (screen and notification) reported success. */
    SUCCESS_CORROBORATED("Success seen on screen and notification", true),
    USER_CONFIRMED("Confirmed by you", true);

    val isSuccess: Boolean get() = this == SUCCESS_REPORTED || this == SUCCESS_CORROBORATED
}

enum class ExtractionMethod(val label: String) {
    ACCESSIBILITY("Screen text"),
    NOTIFICATION("Notification"),
    OCR("Screenshot OCR"),
    MANUAL("Manual"),
}

enum class ReviewIssue(val label: String) {
    AMOUNT_MISSING("Amount not found"),
    AMOUNT_AMBIGUOUS("Several different amounts on screen"),
    AMOUNT_OCR_CORRECTED("Amount needed OCR character fixes"),
    DESCRIPTION_MISSING("No description or note found"),
    STATUS_UNVERIFIED("Payment status could not be verified"),
    STATUS_CONFLICT("Sources disagree about success/failure"),
    NO_PAYMENT_FLOW_SEEN("Success screen seen without a payment flow (maybe an old payment)"),
    POSSIBLE_DUPLICATE("May duplicate another entry"),
    /** "Review before saving" is on: detected automatically, counted once the user checks it. */
    AWAITING_REVIEW("Detected automatically. Check it before it counts"),
}
