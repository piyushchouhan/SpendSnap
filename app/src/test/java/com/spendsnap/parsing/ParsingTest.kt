package com.spendsnap.parsing

import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.ScreenStatus
import com.spendsnap.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountParserTest {
    private fun amount(vararg lines: String) = AmountParser.parse(lines.toList())

    @Test fun `rupee symbol`() = assertEquals(AmountResult.Found(100, "INR", false), amount("₹1"))
    @Test fun `Rs and INR prefixes`() {
        assertEquals(AmountResult.Found(25050, "INR", false), amount("Rs. 250.50"))
        assertEquals(AmountResult.Found(99900, "INR", false), amount("INR 999"))
    }
    @Test fun `indian grouping`() = assertEquals(AmountResult.Found(1_23_456_78, "INR", false), amount("₹1,23,456.78"))
    @Test fun `western grouping`() = assertEquals(AmountResult.Found(1_234_567_00, "INR", false), amount("₹1,234,567"))
    @Test fun `single decimal digit means tens of paise`() = assertEquals(AmountResult.Found(1050, "INR", false), amount("₹10.5"))
    @Test fun `malformed grouping is rejected`() = assertEquals(AmountResult.Missing, amount("₹1,0,0"))
    @Test fun `zero is not an amount`() = assertEquals(AmountResult.Missing, amount("₹0"))
    @Test fun `absurdly large value is rejected`() = assertEquals(AmountResult.Missing, amount("₹99999999999"))
    @Test fun `labeled amount without currency symbol`() = assertEquals(AmountResult.Found(50000, null, false), amount("Amount: 500"))
    @Test fun `label on previous line`() = assertEquals(AmountResult.Found(70000, "INR", false), amount("₹500", "Amount", "₹700"))
    @Test fun `bare number is ignored`() = assertEquals(AmountResult.Missing, amount("500", "20 Oct 2026"))
    @Test fun `same amount repeated is not ambiguous`() = assertEquals(AmountResult.Found(100, "INR", false), amount("₹1", "Paid ₹1 to Ravi"))
    @Test fun `cashback and balance lines are excluded`() =
        assertEquals(AmountResult.Found(30000, "INR", false), AmountParser.parse(fixture("cashback_noise")))
    @Test fun `two unlabeled amounts are ambiguous`() =
        assertEquals(AmountResult.Ambiguous(listOf(45000, 54000)), AmountParser.parse(fixture("ambiguous")))
    @Test fun `OCR letter O and l are corrected but flagged`() =
        assertEquals(AmountResult.Found(100000, "INR", true), amount("Rs. 1,O00"))
    @Test fun `words after currency are not amounts`() = assertEquals(AmountResult.Missing, amount("Rs only"))
}

class DescriptionParserTest {
    @Test fun `inline note`() = assertEquals("Testing", DescriptionParser.note(listOf("Note: Testing")))
    @Test fun `note on next line`() = assertEquals("Testing", DescriptionParser.note(listOf("Note", "Testing")))
    @Test fun `message with separator`() = assertEquals("Team snacks", DescriptionParser.note(listOf("Message: Team snacks")))
    @Test fun `placeholder is not a note`() = assertNull(DescriptionParser.note(listOf("Note", "Add a note")))
    @Test fun `amount is not a note`() = assertNull(DescriptionParser.note(listOf("Note:", "₹500")))
    @Test fun `missing note`() = assertNull(DescriptionParser.note(listOf("Paid to Ravi", "₹1")))
    @Test fun `payee from paid to`() = assertEquals("Ravi Kumar Sharma", DescriptionParser.payee(listOf("Paid to Ravi Kumar Sharma")))
    @Test fun `payee from notification sentence`() =
        assertEquals("Chai Point", DescriptionParser.payee(listOf("Paid ₹40 to Chai Point successfully")))
}

class StatusClassifierTest {
    private fun status(name: String) = StatusClassifier.classify(fixture(name))

    @Test fun success() {
        assertEquals(ScreenStatus.SUCCESS, status("gpay_success"))
        assertEquals(ScreenStatus.SUCCESS, status("phonepe_success"))
    }
    @Test fun `failed screen mentioning paid and debited is still failed`() = assertEquals(ScreenStatus.FAILED, status("failed"))
    @Test fun pending() = assertEquals(ScreenStatus.PENDING, status("pending"))
    @Test fun `amount entry`() = assertEquals(ScreenStatus.AMOUNT_ENTRY, status("amount_entry"))
    @Test fun `real GPay home screen is not a payment flow`() = assertEquals(ScreenStatus.UNKNOWN, status("gpay_home_real"))
    @Test fun `incoming money is not an expense`() = assertEquals(ScreenStatus.NOT_AN_EXPENSE, status("incoming"))
    @Test fun `not successful is failure`() =
        assertEquals(ScreenStatus.FAILED, StatusClassifier.classify(listOf("Payment not successful")))
    @Test fun `conflicting strong signals are unknown`() =
        assertEquals(ScreenStatus.UNKNOWN, StatusClassifier.classify(listOf("Payment successful", "Payment failed")))
    @Test fun `no signals is unknown`() = assertEquals(ScreenStatus.UNKNOWN, StatusClassifier.classify(listOf("Hello")))
}

class PaymentExtractorTest {
    @Test fun `spec example`() {
        val e = PaymentExtractor.extract(fixture("spec_example"))!!
        assertEquals("Testing", e.description)
        assertEquals(100L, e.amountPaise)
        assertEquals("INR", e.currency)
        // No success indicator on this text, so it must not be treated as a confirmed payment.
        assertEquals(ScreenStatus.UNKNOWN, e.status)
        assertTrue(ReviewIssue.STATUS_UNVERIFIED in e.issues)
    }

    @Test fun `gpay success has reference and no issues`() {
        val e = PaymentExtractor.extract(fixture("gpay_success"))!!
        assertEquals("Testing", e.description)
        assertEquals(100L, e.amountPaise)
        assertEquals("629311845521", e.transactionReference)
        assertTrue(e.issues.isEmpty())
    }

    @Test fun `phonepe success`() {
        val e = PaymentExtractor.extract(fixture("phonepe_success"))!!
        assertEquals("Team snacks", e.description)
        assertEquals(125050L, e.amountPaise)
        assertEquals("T2610202141123456789012", e.transactionReference)
        assertEquals(ScreenStatus.SUCCESS, e.status)
    }

    @Test fun `missing description falls back to payee`() {
        val e = PaymentExtractor.extract(fixture("failed"))!!
        assertEquals("Ramesh Kirana", e.description)
        assertEquals(ScreenStatus.FAILED, e.status)
    }

    @Test fun `no description at all is flagged`() {
        val e = PaymentExtractor.extract(fixture("pending"))!!
        assertNull(e.description)
        assertTrue(ReviewIssue.DESCRIPTION_MISSING in e.issues)
        assertTrue(ReviewIssue.STATUS_UNVERIFIED in e.issues)
    }

    @Test fun `ambiguous amount is not guessed`() {
        val e = PaymentExtractor.extract(fixture("ambiguous"))!!
        assertNull(e.amountPaise)
        assertEquals(listOf(45000L, 54000L), e.amountCandidates)
        assertTrue(ReviewIssue.AMOUNT_AMBIGUOUS in e.issues)
    }

    @Test fun `noisy OCR is corrected but sent to review`() {
        val e = PaymentExtractor.extract(fixture("ocr_noisy"))!!
        assertEquals(100000L, e.amountPaise)
        assertTrue(ReviewIssue.AMOUNT_OCR_CORRECTED in e.issues)
    }

    @Test fun `UPI PIN screen is dropped`() = assertNull(PaymentExtractor.extract(fixture("upi_pin")))
    @Test fun `OTP text is dropped`() = assertNull(PaymentExtractor.extract(listOf("Your OTP is 123456", "₹1")))

    @Test fun `reference needs a label`() {
        assertNull(ReferenceParser.find(listOf("629311845521")))
        assertFalse(ReferenceParser.find(listOf("UTR: 629311845599")).isNullOrEmpty())
    }
}

class LineTaggerTest {
    @Test fun `phonepe layout with payee on the next line`() {
        val lines = listOf("Transaction Successful", "Paid to", "Lakshmi General Stores", "₹420", "Debited from ••••7712")
        val e = PaymentExtractor.extract(lines)!!
        assertEquals("Lakshmi General Stores", e.description)
        assertEquals("Paid to", e.descriptionRule)
        assertEquals(
            listOf(null, LineTag.ANCHOR, LineTag.DESCRIPTION, LineTag.AMOUNT, null),
            LineTagger.tag(lines, e.amountPaise, e.description).map { it.second },
        )
    }
}
