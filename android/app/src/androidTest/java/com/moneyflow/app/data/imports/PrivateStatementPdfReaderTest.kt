package com.moneyflow.app.data.imports

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** Opt-in device verification. Fixtures are streamed into private app storage, never bundled. */
class PrivateStatementPdfReaderTest {
    @Test fun privatePdfMatchesIndependentOracle() {
        val args = InstrumentationRegistry.getArguments()
        val pdfName = args.getString("privatePdfFixture")
        val oracleName = args.getString("privateOracleFixture")
        assumeTrue("Private device verification is opt-in", pdfName != null && oracleName != null)
        require(pdfName!!.matches(Regex("[A-Za-z0-9_-]+\\.pdf")))
        require(oracleName!!.matches(Regex("[A-Za-z0-9_-]+\\.json")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "pdf-import-private")
        val fixture = File(dir, pdfName)
        if (args.getString("privateDiagnose") == "true") {
            val reader = StatementPdfReader(context)
            PDDocument.load(fixture).use { document ->
                val enumerated = document.pages.toList()
                val pages = reader.extractNativePages(document)
                println("Private native diagnostics: declaredPages=${document.numberOfPages}, iteratedPages=${enumerated.size}, contentPages=${enumerated.count { it.hasContents() }}, extractedPages=${pages.size}, words=${pages.sumOf { it.words.size }}")
                // Only an explicit private test may export source text, to private app files.
                File(dir, "native-words.json").writeText(Gson().toJson(pages), Charsets.UTF_8)
            }
        }
        val expectedHash = fixture.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val parsed = fixture.inputStream().use { StatementPdfReader(context).read(it) }
        val statement = parsed.statement
        assertTrue("Native PDF blocking errors: ${statement.errors.joinToString()}", statement.errors.isEmpty())
        assertTrue("Local PDF SHA-256 differs", parsed.fileSha256 == expectedHash)
        args.getString("expectedBank")?.let { assertTrue("Bank differs", statement.bank == it) }
        args.getString("expectedRows")?.toInt()?.let { assertTrue("Row count differs", statement.rows.size == it) }
        val oracle = File(dir, oracleName).reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray }
        val expected = oracle.flatMap { item ->
            val row = item.asJsonObject
            val d = row["date"].asString.split('.')
            val date = LocalDate.of(if (d[2].length == 2) 2000 + d[2].toInt() else d[2].toInt(), d[1].toInt(), d[0].toInt())
            if (statement.bank == "kaspi") listOf(Triple(date, row["kind"].asString, row["minor"].asLong))
            else listOf("credit", "debit", "commission").map { row[it].asLong }.filter { it != 0L }
                .map { Triple(date, if (it > 0L) "income" else "expense", kotlin.math.abs(it)) }
        }
        val actual = statement.rows.map { Triple(it.date, it.kind, it.amountMinor) }
        assertTrue("Native PDF ledger differs from independent private oracle", actual == expected)
        println("Private native PDF verification passed: bank=${statement.bank}, ledgerRows=${actual.size}, reconciliation=true, sha256Match=true")
    }
}
