package com.moneyflow.app.data.imports

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs

/** Pure, deterministic parsers for the verified Russian Kaspi Gold/Halyk KZT layouts. */
object StatementParser {
    private val datePattern = Regex("\\d{2}\\.\\d{2}\\.\\d{2}(?:\\d{2})?")
    private val moneyPattern = Regex("[+-]?\\d[\\d \\u00a0\\u202f]*[,.]\\d{2}")
    private const val MAX_ROWS = 2000

    fun parse(pages: List<PdfTextPage>): ParsedStatement {
        if (pages.isEmpty() || pages.size > 100) throw StatementImportException("Неподдерживаемое число страниц PDF.")
        if (pages.sumOf { it.words.sumOf { word -> word.text.length } } > 2_000_000) {
            throw StatementImportException("В PDF слишком много текста.")
        }
        val text = pages.joinToString(" ") { page -> lines(page).joinToString(" ") { it.text } }
        val normalized = compact(text)
        if (normalized.isBlank()) throw StatementImportException("В PDF нет текста. Сканы и OCR пока не поддерживаются.")
        val kaspi = "kaspigold" in normalized && "операция" in normalized && "детали" in normalized
        val halyk = "приходв" in normalized && "расходв" in normalized && "валютесчета" in normalized && "входящийостаток" in normalized
        if (kaspi == halyk) throw StatementImportException("Формат не распознан. Поддерживаются русские PDF Kaspi Gold и Halyk в KZT.")
        return if (kaspi) parseKaspi(pages, text) else parseHalyk(pages, text)
    }

    private fun parseKaspi(pages: List<PdfTextPage>, text: String): ParsedStatement {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<StatementRow>()
        val period = period(text)
        val balanceLines = pages.flatMap(::lines).filter { line ->
            compact(line.text).startsWith("доступнона") && "₸" in line.text
        }.mapNotNull { line ->
            val dateMatch = datePattern.find(line.text)
            val date = dateMatch?.value?.let(::date)
            val amountText = dateMatch?.let { line.text.substring(it.range.last + 1) } ?: ""
            val amount = moneyPattern.find(normalizeMinus(amountText))?.value?.let(::money)
            if (date != null && amount != null) date to amount else null
        }
        val opening = balanceLines.firstOrNull { it.first == period?.first }?.second
        val closing = balanceLines.lastOrNull { it.first == period?.second }?.second
        val currencyLine = pages.flatMap(::lines).firstOrNull { "валютасчета:" in compact(it.text) }
        if (currencyLine != null && "тенге" !in compact(currencyLine.text) && "теңге" !in compact(currencyLine.text) && "kzt" !in compact(currencyLine.text)) {
            errors.add("В первой версии поддерживаются только счета KZT.")
        }
        var sawTable = false
        var layout: KaspiLayout? = null
        pages.forEach { page ->
            val pageLines = lines(page)
            val header = pageLines.firstOrNull { line ->
                val t = compact(line.text)
                "дата" in t && "сумма" in t && "операция" in t && "детали" in t
            }
            val datedLines = pageLines.filter { line -> line.words.any { it.x < page.width * .15f && datePattern.matches(it.text) } }
            if (header == null && (layout == null || datedLines.isEmpty())) {
                // Known certificate/explanatory pages have no dated transaction-column rows.
                if (datedLines.isNotEmpty()) {
                    errors.add("Страница ${page.number}: строки операций найдены без известного заголовка таблицы.")
                }
                return@forEach
            }
            sawTable = true
            if (header != null) {
                val dateHeader = header.words.firstOrNull { compact(it.text) == "дата" }
                val amountHeader = header.words.firstOrNull { compact(it.text) == "сумма" }
                val operationHeader = header.words.firstOrNull { compact(it.text) == "операция" }
                val detailsHeader = header.words.firstOrNull { compact(it.text) == "детали" }
                if (listOf(dateHeader, amountHeader, operationHeader, detailsHeader).any { it == null }) {
                    errors.add("Страница ${page.number}: неподдерживаемые колонки Kaspi."); return@forEach
                }
                layout = KaspiLayout(page.width, midpoint(dateHeader!!, amountHeader!!),
                    midpoint(amountHeader, operationHeader!!), detailsHeader!!.x - 3f)
            }
            val current = layout!!
            if (abs(current.width - page.width) > 1f) {
                errors.add("Страница ${page.number}: изменился размер таблицы Kaspi."); return@forEach
            }
            val amountLeft = current.amountLeft
            val operationLeft = current.operationLeft
            val detailsLeft = current.detailsLeft
            // Native Kaspi continuation pages omit column headings; retain the verified geometry.
            val start = header?.let { it.y + 3f } ?: (datedLines.first().y - 1f)
            val content = pageLines.filter { it.y > start && it.y < page.height - 45f }
            val groups = transactionGroups(content, amountLeft, errors, page.number)
            groups.forEach { group ->
                val first = group.first()
                val dateValue = first.words.filter { it.x < amountLeft }.joinText().trim()
                val parsedDate = date(dateValue)
                val amountCell = first.words.filter { it.x >= amountLeft && it.x < operationLeft }.joinText()
                val normalizedAmount = normalizeMinus(amountCell).replace(" ", "").replace("\u00a0", "").replace("\u202f", "")
                val amountMatch = Regex("([+-]\\d+[,.]\\d{2})₸").matchEntire(normalizedAmount)
                val signedAmount = amountMatch?.groupValues?.get(1)?.let(::money)
                val operation = group.flatMap { it.words }.filter { it.x >= operationLeft && it.x < detailsLeft }.joinText()
                val details = group.flatMap { it.words }.filter { it.x >= detailsLeft }.joinText()
                val invalidAmountContinuation = group.drop(1).any { line ->
                    val value = line.words.filter { it.x >= amountLeft && it.x < operationLeft }.joinText()
                    // Kaspi prints the original FX price on a separate parenthesized line.
                    value.isNotBlank() && !Regex("\\([+-]?\\d+[,.]\\d{2}[A-Z]{3}\\)")
                        .matches(normalizeMinus(value).replace(Regex("[\\s\\u00a0\\u202f]"), ""))
                }
                if (parsedDate == null || signedAmount == null || signedAmount == 0L || operation.isBlank() || invalidAmountContinuation) {
                    errors.add("Страница ${page.number}: не удалось полностью прочитать строку операции ${rows.size + 1}.")
                    return@forEach
                }
                addRow(rows, parsedDate, signedAmount, "$operation: $details".trimEnd(':', ' '), page.number, errors)
            }
        }
        if (!sawTable) errors.add("Таблица операций Kaspi не найдена.")
        return finish("kaspi", period, opening, closing, rows, warnings, errors)
    }

    private fun parseHalyk(pages: List<PdfTextPage>, text: String): ParsedStatement {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val rows = mutableListOf<StatementRow>()
        val period = period(text)
        val normalized = compact(normalizeMinus(text))
        fun balance(label: String): Long? {
            val part = normalized.substringAfter(label, "")
            return Regex("^:([+-]?\\d+[,.]\\d{2})kzt").find(part)?.groupValues?.get(1)?.let(::money)
        }
        val opening = balance("входящийостаток")
        val closing = balance("исходящийостаток")
        var creditTotal: Long? = null
        var debitTotal: Long? = null
        var feeTotal: Long? = null
        val parsedCredits = mutableListOf<Long>()
        val parsedDebits = mutableListOf<Long>()
        val parsedFees = mutableListOf<Long>()
        var sawTable = false
        pages.forEach { page ->
            val pageLines = lines(page)
            val credit = page.words.firstOrNull { compact(it.text).startsWith("приход") }
            val debit = page.words.firstOrNull { compact(it.text).startsWith("расход") }
            val fee = page.words.firstOrNull { compact(it.text) == "комиссия" }
            val amount = page.words.firstOrNull { compact(it.text) == "сумма" }
            val currency = page.words.firstOrNull { compact(it.text) == "валюта" }
            val desc = page.words.firstOrNull { compact(it.text).startsWith("описание") }
            val dateHeaders = page.words.filter { compact(it.text) == "дата" }.sortedBy { it.x }
            val card = page.words.firstOrNull { compact(it.text).contains("карточки/счета") }
            if (credit == null || debit == null || fee == null || amount == null || currency == null || desc == null || dateHeaders.size != 2 || card == null) {
                errors.add("Страница ${page.number}: неизвестные колонки Halyk."); return@forEach
            }
            sawTable = true
            val positions = listOf(dateHeaders[0], dateHeaders[1], desc, amount, currency, credit, debit, fee, card)
            if (positions.zipWithNext().any { (a, b) -> a.x >= b.x }) {
                errors.add("Страница ${page.number}: неизвестный порядок колонок Halyk."); return@forEach
            }
            val bounds = positions.zipWithNext().map { (a, b) -> midpoint(a, b) }
            // The description's title is longer than its column. Its start is the reliable left edge.
            val descriptionLeft = (dateHeaders[1].x + dateHeaders[1].width + desc.x) / 2f
            val descriptionRight = amount.x - 8f
            val currencyLeft = midpoint(amount, currency)
            val creditLeft = midpoint(currency, credit)
            val debitLeft = midpoint(credit, debit)
            val feeLeft = midpoint(debit, fee)
            val cardLeft = card.x - 12f
            val headerBottom = pageLines.filter { abs(it.y - fee.y) <= 12f }.maxOfOrNull { it.y } ?: fee.y
            val stamp = pageLines.firstOrNull { "местопечатибанка" in compact(it.text) }?.y ?: page.height
            val content = pageLines.filter { it.y > headerBottom + 3f && it.y < minOf(page.height - 45f, stamp) }
            val totalLine = content.firstOrNull { line -> line.words.any { it.x < descriptionLeft && compact(it.text) == "kzt" } }
            fun cell(line: Line, left: Float, right: Float) = line.words.filter { it.x >= left && it.x < right }.joinText()
            if (totalLine != null) {
                creditTotal = money(cell(totalLine, creditLeft, debitLeft))
                debitTotal = money(cell(totalLine, debitLeft, feeLeft))
                feeTotal = money(cell(totalLine, feeLeft, cardLeft))
            }
            val groups = transactionGroups(content.filter { line ->
                line != totalLine && compact(line.text) != "всего:"
            }, bounds[0], errors, page.number)
            groups.forEach { group ->
                val first = group.first()
                val transactionDate = date(cell(first, Float.NEGATIVE_INFINITY, bounds[0]))
                val processingDate = date(cell(first, bounds[0], descriptionLeft))
                val creditAmount = money(cell(first, creditLeft, debitLeft))
                val debitAmount = money(cell(first, debitLeft, feeLeft))
                val feeAmount = money(cell(first, feeLeft, cardLeft))
                val originalCurrency = compact(cell(first, currencyLeft, creditLeft))
                val note = group.flatMap { it.words }.filter { it.x >= descriptionLeft && it.x < descriptionRight }.joinText()
                val extraSettledCell = group.drop(1).any { line ->
                    line.words.any { it.x >= creditLeft && it.x < cardLeft }
                }
                // The settled amount columns are authoritative; original FX amounts are never added.
                if (transactionDate == null || processingDate == null || creditAmount == null || debitAmount == null || feeAmount == null ||
                    creditAmount < 0 || debitAmount > 0 || feeAmount > 0 || note.isBlank() || extraSettledCell ||
                    (creditAmount != 0L && debitAmount != 0L) ||
                    (creditAmount == 0L && debitAmount == 0L && feeAmount == 0L) ||
                    (originalCurrency.isNotEmpty() && !Regex("[a-z]{3}").matches(originalCurrency))) {
                    errors.add("Страница ${page.number}: не удалось полностью прочитать строку Halyk ${rows.size + 1}.")
                    return@forEach
                }
                parsedCredits.add(creditAmount)
                parsedDebits.add(debitAmount)
                parsedFees.add(feeAmount)
                if (creditAmount != 0L || debitAmount != 0L) {
                    addRow(rows, transactionDate, if (creditAmount != 0L) creditAmount else debitAmount, note, page.number, errors)
                }
                if (feeAmount != 0L) addRow(rows, transactionDate, feeAmount, "Комиссия: $note", page.number, errors, possibleTransfer = false)
            }
        }
        if (!sawTable) errors.add("Таблица операций Halyk не найдена.")
        if (creditTotal == null || debitTotal == null || feeTotal == null) {
            errors.add("Не удалось прочитать итоговые обороты Halyk в KZT.")
        } else {
            val income = sum(parsedCredits)
            val fees = sum(parsedFees)
            val expense = sum(parsedDebits)
            if (income != creditTotal || expense != debitTotal || fees != feeTotal) {
                errors.add("Обороты операций не совпадают с итоговой строкой Halyk. Импорт заблокирован.")
            }
        }
        return finish("halyk", period, opening, closing, rows, warnings, errors)
    }

    private fun addRow(rows: MutableList<StatementRow>, date: LocalDate, signed: Long, note: String, page: Int,
                       errors: MutableList<String>, possibleTransfer: Boolean = isTransfer(note)) {
        if (rows.size >= MAX_ROWS) throw StatementImportException("Поддерживается не более 2000 операций, включая комиссии.")
        if (signed == Long.MIN_VALUE) { errors.add("Сумма операции превышает допустимый размер."); return }
        if (note.codePointCount(0, note.length) > 500) { errors.add("Описание операции слишком длинное для безопасного импорта."); return }
        rows.add(StatementRow(rows.size + 1, date, if (signed > 0) "income" else "expense", abs(signed), note, possibleTransfer, page))
    }

    private fun finish(bank: String, period: Pair<LocalDate, LocalDate>?, opening: Long?, closing: Long?,
                       rows: List<StatementRow>, warnings: MutableList<String>, errors: MutableList<String>): ParsedStatement {
        if (period == null || period.first > period.second) errors.add("Не удалось определить корректный период выписки.")
        if (opening == null || closing == null) errors.add("Не удалось прочитать начальный и конечный остатки KZT.")
        if (period != null && rows.any { it.date < period.first || it.date > period.second }) errors.add("Дата операции выходит за период выписки.")
        if (rows.isEmpty()) errors.add("В выписке не найдено операций для импорта.")
        if (opening != null && closing != null) {
            val change = sum(rows.map { if (it.kind == "income") it.amountMinor else -it.amountMinor })
            val expected = change?.let { add(opening, it) }
            if (expected == null || expected != closing) errors.add("Начальный остаток и операции не совпадают с конечным остатком. Проверьте выписку.")
        }
        if (rows.any { it.possibleTransfer }) warnings.add("Переводы и снятия наличных требуют проверки: перевод между своими счетами не является доходом или расходом.")
        return ParsedStatement(bank, "KZT", period?.first, period?.second, opening, closing, rows, warnings.distinct(), errors.distinct())
    }

    private data class Line(val y: Float, val words: List<PdfWord>) { val text get() = words.joinText() }
    private data class KaspiLayout(val width: Float, val amountLeft: Float, val operationLeft: Float, val detailsLeft: Float)

    private fun lines(page: PdfTextPage): List<Line> {
        val grouped = mutableListOf<MutableList<PdfWord>>()
        page.words.sortedWith(compareBy<PdfWord> { it.y }.thenBy { it.x }).forEach { word ->
            val last = grouped.lastOrNull()
            if (last == null || abs(last.first().y - word.y) > 2f) grouped.add(mutableListOf(word)) else last.add(word)
        }
        return grouped.map { Line(it.first().y, it.sortedBy { word -> word.x }) }
    }

    private fun transactionGroups(lines: List<Line>, dateRight: Float, errors: MutableList<String>, page: Int,
                                  allowPreamble: Boolean = false): List<List<Line>> {
        val groups = mutableListOf<MutableList<Line>>()
        lines.forEach { line ->
            val dateCell = line.words.filter { it.x < dateRight }.joinText().trim()
            if (datePattern.matches(dateCell)) groups.add(mutableListOf(line))
            else if (dateCell.isNotBlank()) {
                // A malformed date column must not become part of the previous transaction.
                if (!allowPreamble || groups.isNotEmpty()) errors.add("Страница $page: неизвестная строка в колонке даты.")
            } else if (groups.isNotEmpty()) groups.last().add(line)
            else if (!allowPreamble && line.words.isNotEmpty()) errors.add("Страница $page: продолжение операции без даты.")
        }
        return groups
    }

    private fun period(text: String): Pair<LocalDate, LocalDate>? {
        val match = Regex("период(?:выписки)?[:]*с(${datePattern.pattern})по(${datePattern.pattern})").find(compact(text)) ?: return null
        val start = date(match.groupValues[1]) ?: return null
        val end = date(match.groupValues[2]) ?: return null
        return start to end
    }

    private fun date(value: String): LocalDate? {
        val t = value.trim().split('.')
        if (t.size != 3 || !datePattern.matches(value.trim())) return null
        return try { LocalDate.of(if (t[2].length == 2) 2000 + t[2].toInt() else t[2].toInt(), t[1].toInt(), t[0].toInt()) } catch (_: Exception) { null }
    }

    private fun money(value: String): Long? {
        val normalized = normalizeMinus(value).replace(Regex("[\\s\\u00a0\\u202f]"), "").replace(',', '.')
        if (!Regex("[+-]?\\d+\\.\\d{2}").matches(normalized)) return null
        return try { BigDecimal(normalized).movePointRight(2).longValueExact() } catch (_: ArithmeticException) { null }
    }

    private fun compact(text: String) = text.lowercase().replace(Regex("[\\s\\u00a0\\u202f]"), "")
    private fun normalizeMinus(text: String) = text.replace('−', '-').replace('–', '-').replace('—', '-')
    private fun List<PdfWord>.joinText() = joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
    private fun midpoint(a: PdfWord, b: PdfWord) = (a.x + a.width / 2f + b.x + b.width / 2f) / 2f
    private fun isTransfer(note: String): Boolean {
        val t = compact(note)
        return listOf("перевод", "снятие", "снятия", "наличн", "сосвоего", "насвой", "междусвоими", "перечислен").any { it in t }
    }
    private fun sum(values: List<Long>): Long? = try { values.fold(0L, Math::addExact) } catch (_: ArithmeticException) { null }
    private fun add(a: Long, b: Long): Long? = try { Math.addExact(a, b) } catch (_: ArithmeticException) { null }
}
