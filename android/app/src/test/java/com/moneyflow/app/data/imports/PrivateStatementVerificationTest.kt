package com.moneyflow.app.data.imports

import com.google.gson.JsonParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Optional local verification. Private inputs/oracles live outside Git; output contains counts only. */
class PrivateStatementVerificationTest {
    @Test fun privateNativeLayoutMatchesIndependentOracle() {
        val path = System.getenv("MONEYFLOW_PRIVATE_PDF_FIXTURE_DIR")
        assumeTrue("Private sample verification is opt-in", !path.isNullOrBlank())
        val dir = File(path!!)
        for (bank in listOf("kaspi", "halyk")) {
            val source = File(dir, "$bank-words.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray }
            val pages = source.mapIndexed { index, page ->
                PdfTextPage(index + 1, 595f, 842f, page.asJsonArray.map { raw ->
                    val word = raw.asJsonObject
                    PdfWord(word["text"].asString, word["x0"].asFloat, word["top"].asFloat,
                        word["x1"].asFloat - word["x0"].asFloat)
                })
            }
            val parsed = StatementParser.parse(pages)
            assertTrue("$bank parser reported blocking errors: ${parsed.errors.joinToString()}", parsed.errors.isEmpty())
            val oracle = File(dir, "$bank-private-oracle.json").reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray }
            val expected = oracle.flatMap { item ->
                val row = item.asJsonObject
                val d = row["date"].asString.split('.')
                val date = LocalDate.of(if (d[2].length == 2) 2000 + d[2].toInt() else d[2].toInt(), d[1].toInt(), d[0].toInt())
                if (bank == "kaspi") listOf(Triple(date, row["kind"].asString, row["minor"].asLong))
                else listOf("credit", "debit", "commission").map { row[it].asLong }.filter { it != 0L }
                    .map { Triple(date, if (it > 0L) "income" else "expense", kotlin.math.abs(it)) }
            }
            val actual = parsed.rows.map { Triple(it.date, it.kind, it.amountMinor) }
            assertTrue("$bank parsed ledger differs from independent private oracle", actual == expected)
            println("Private $bank verification passed: pages=${pages.size}, sourceRows=${oracle.size()}, ledgerRows=${actual.size}, reconciliation=true")
        }
    }
}
