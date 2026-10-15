package com.moneyflow.app.ui

import com.moneyflow.app.data.imports.KazakhstanStatementTime
import com.moneyflow.app.data.imports.ParsedPdfStatement
import com.moneyflow.app.data.remote.ImportPreviewRequest
import com.moneyflow.app.data.remote.ImportSourceRow

/** Constructs only a new preview from date-only PDF rows; never rewrites existing preview/retry bodies. */
internal fun ParsedPdfStatement.toPreview(accountId: String): ImportPreviewRequest = ImportPreviewRequest(
    accountId, statement.bank, statement.currency, fileSha256, KazakhstanStatementTime.TIMEZONE,
    statement.openingBalanceMinor?.toString(), statement.closingBalanceMinor?.toString(),
    statement.rows.map { row ->
        ImportSourceRow(row.rowId, KazakhstanStatementTime.startOfDay(row.date).toString(),
            row.kind, row.amountMinor.toString(), normalizeBankNote(row.note))
    },
)
