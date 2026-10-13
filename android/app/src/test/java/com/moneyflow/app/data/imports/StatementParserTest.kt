package com.moneyflow.app.data.imports

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class StatementParserTest {
    @Test fun kaspiCoverMultiPageAndWrappedDescriptionReconcile() {
        val cover = page(1, listOf(w("Kaspi Gold", 40f, 50f), w("Справка", 40f, 70f)))
        val first = kaspiPage(2, listOf(kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тестовый доход", 170f)))
        val second = kaspiPage(3, listOf(kaspiRow("05.01.26", "−100,00", "Покупка", "Тестовое кафе", 170f),
            listOf(w("Вторая строка", 311f, 185f))))
        val parsed = StatementParser.parse(listOf(cover, first, second))
        assertEquals(emptyList<String>(), parsed.errors)
        assertEquals("kaspi", parsed.bank)
        assertEquals(2, parsed.rows.size)
        assertEquals(100_000L, parsed.rows.first().amountMinor)
        assertEquals("Покупка: Тестовое кафе Вторая строка", parsed.rows.last().note)
        assertEquals(3, parsed.rows.last().sourcePage)
    }

    @Test fun halykUsesSettledAmountsAndAddsCommissionSeparately() {
        val words = halykHeader() + listOf(
            halykRow("03.01.2026", "Тестовый доход", "10,00", "USD", "1 000,00", "0,00", "0,00", 170f),
            halykRow("05.01.2026", "Тестовая покупка", "2,00", "USD", "0,00", "-90,00", "-10,00", 200f),
            halykRow("06.01.2026", "Тестовая комиссия", "0,00", "", "0,00", "0,00", "-10,00", 230f),
        ).flatten()
        val parsed = StatementParser.parse(listOf(page(1, words)))
        assertEquals(emptyList<String>(), parsed.errors)
        assertEquals(4, parsed.rows.size)
        assertEquals(listOf(100_000L, 9_000L, 1_000L, 1_000L), parsed.rows.map { it.amountMinor })
        assertTrue(parsed.rows.none { "TEST-CARD" in it.note })
    }

    @Test fun malformedDateCannotBeSilentlySkipped() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1, listOf(
            kaspiRow("31.02.26", "+1 000,00", "Пополнение", "Тест", 170f),
            kaspiRow("05.01.26", "-100,00", "Покупка", "Тест", 200f),
        ))))
        assertTrue(parsed.errors.isNotEmpty())
    }

    @Test fun changedLayoutOnContinuationPageBlocksImport() {
        val first = kaspiPage(1, listOf(kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тест", 170f)))
        val unknown = page(2, listOf(w("05.01.26", 52f, 100f), w("-100,00 ₸", 400f, 100f)))
        val parsed = StatementParser.parse(listOf(first, unknown))
        assertTrue(parsed.errors.isNotEmpty())
    }

    @Test fun inconsistentClosingBalanceBlocksImport() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1,
            listOf(kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тест", 170f)))))
        assertTrue(parsed.errors.any { "остатком" in it })
    }

    @Test fun transferFlagRequiresReview() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1, listOf(
            kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тест", 170f),
            kaspiRow("05.01.26", "-100,00", "Перевод", "Тестовый получатель", 200f),
        ))))
        assertTrue(parsed.rows.last().possibleTransfer)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun originalKaspiForeignPriceIsNotAddedTwice() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1, listOf(
            kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тест", 170f),
            kaspiRow("05.01.26", "-100,00", "Покупка", "Тест", 200f),
            listOf(w("(-20,00 KGS)", 140f, 215f)),
        ))))
        assertTrue(parsed.errors.isEmpty())
        assertEquals(2, parsed.rows.size)
        assertEquals(10_000L, parsed.rows.last().amountMinor)
    }

    @Test fun amountWithoutDateIsNotSilentlyIgnored() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1, listOf(
            kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тест", 170f),
            kaspiRow("05.01.26", "-100,00", "Покупка", "Тест", 200f),
            listOf(w("-300,00 ₸", 140f, 215f)),
        ))))
        assertTrue(parsed.errors.isNotEmpty())
    }

    @Test fun overflowingAmountIsRejected() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1,
            listOf(kaspiRow("03.01.26", "+99999999999999999999999,99", "Пополнение", "Тест", 170f)))))
        assertTrue(parsed.errors.isNotEmpty())
        assertTrue(parsed.rows.isEmpty())
    }

    @Test fun unknownFormatAndScansGiveExplicitFailure() {
        listOf(emptyList<PdfWord>(), listOf(w("Other Bank", 20f, 40f))).forEach { words ->
            try { StatementParser.parse(listOf(page(1, words))); fail("Unsupported document must fail") }
            catch (_: StatementImportException) { /* Expected. */ }
        }
    }

    @Test fun dateOutsidePeriodBlocksImport() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1, listOf(
            kaspiRow("03.02.26", "+1 000,00", "Пополнение", "Тест", 170f),
            kaspiRow("05.01.26", "-100,00", "Покупка", "Тест", 200f),
        ))))
        assertTrue(parsed.errors.any { "период" in it })
    }

    @Test fun missingHalykSettledCellBlocksImport() {
        val words = halykHeader() + halykRow("03.01.2026", "Тест", "1 000,00", "KZT", "???", "0,00", "0,00", 170f)
        val parsed = StatementParser.parse(listOf(page(1, words)))
        assertTrue(parsed.errors.isNotEmpty())
    }

    @Test fun periodDatesUseCalendarValidation() {
        val parsed = StatementParser.parse(listOf(kaspiPage(1, listOf(
            kaspiRow("03.01.26", "+1 000,00", "Пополнение", "Тест", 170f),
            kaspiRow("05.01.26", "-100,00", "Покупка", "Тест", 200f),
        ))))
        assertEquals(LocalDate.of(2026, 1, 1), parsed.periodStart)
        assertEquals(LocalDate.of(2026, 1, 31), parsed.periodEnd)
    }

    private fun kaspiPage(number: Int, rows: List<List<PdfWord>>) = page(number, listOf(
        w("Выписка по Kaspi Gold за период с 01.01.2026 по 31.01.2026", 40f, 40f),
        w("Валюта счета: Тенге", 327f, 60f),
        w("Доступно на 01.01.2026", 43f, 80f), w("+0,00 ₸", 250f, 80f),
        w("Доступно на 31.01.2026", 43f, 100f), w("+900,00 ₸", 250f, 100f),
        w("Дата", 59f, 140f, 25f), w("Сумма", 134f, 140f, 38f),
        w("Операция", 243f, 140f, 55f), w("Детали", 312f, 140f, 45f),
    ) + rows.flatten())

    private fun kaspiRow(date: String, amount: String, operation: String, description: String, y: Float) = listOf(
        w(date, 52f, y), w("$amount ₸", 135f, y), w(operation, 230f, y), w(description, 311f, y),
    )

    private fun halykHeader() = listOf(
        w("Выписка Halyk Период выписки: с 01.01.2026 по 31.01.2026", 20f, 30f),
        w("Входящийостаток:0,00KZT;0,00USD;0,00EUR;", 340f, 50f),
        w("Исходящийостаток:890,00KZT;0,00USD;0,00EUR;", 340f, 65f),
        w("Дата", 35f, 100f, 18f), w("Дата", 81f, 100f, 18f),
        w("Описаниеоперации", 128f, 109f, 80f), w("Сумма", 228f, 105f, 25f),
        w("Валюта", 273f, 105f, 30f), w("Приходв", 325f, 105f, 34f), w("Расходв", 392f, 105f, 34f),
        w("валютесчета", 318f, 114f, 52f), w("валютесчета", 383f, 114f, 52f),
        w("Комиссия", 447f, 109f, 36f), w("№карточки/счета", 500f, 109f, 65f),
        w("Всего:", 32f, 130f), w("KZT", 35f, 143f),
        w("1 000,00", 324f, 143f), w("-90,00", 392f, 143f), w("-20,00", 453f, 143f),
    )

    private fun halykRow(date: String, description: String, original: String, currency: String, credit: String,
                         debit: String, fee: String, y: Float) = listOf(
        w(date, 26f, y), w(date, 72f, y), w(description, 125f, y), w(original, 230f, y),
        w(currency, 279f, y), w(credit, 324f, y), w(debit, 392f, y), w(fee, 453f, y), w("TEST-CARD", 492f, y),
    )
    private fun page(number: Int, words: List<PdfWord>) = PdfTextPage(number, 595f, 842f, words)
    private fun w(text: String, x: Float, y: Float, width: Float = 20f) = PdfWord(text, x, y, width)
}
