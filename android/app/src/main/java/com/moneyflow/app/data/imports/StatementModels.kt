package com.moneyflow.app.data.imports

import java.time.LocalDate

data class StatementRow(
    val rowId: Int,
    val date: LocalDate,
    val kind: String,
    val amountMinor: Long,
    val note: String,
    val possibleTransfer: Boolean = false,
    val sourcePage: Int,
)

data class ParsedStatement(
    val bank: String,
    val currency: String,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val openingBalanceMinor: Long?,
    val closingBalanceMinor: Long?,
    val rows: List<StatementRow>,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

data class ParsedPdfStatement(val statement: ParsedStatement, val fileSha256: String)

class StatementImportException(message: String) : Exception(message)

/** Position-bearing native text only. Coordinates are page-relative PDF points. */
data class PdfWord(val text: String, val x: Float, val y: Float, val width: Float)
data class PdfTextPage(val number: Int, val width: Float, val height: Float, val words: List<PdfWord>)
