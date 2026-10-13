package com.moneyflow.app.data.imports

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlin.math.abs

/** Reads a selected PDF locally. No network, cache files or financial-data logging. */
class StatementPdfReader(context: Context) {
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    fun read(input: InputStream): ParsedPdfStatement {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            checkInterrupted()
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_BYTES) {
                throw StatementImportException("PDF превышает ограничение 20 МБ.")
            }
            bytes.write(buffer, 0, count)
        }
        val data = bytes.toByteArray()
        if (data.size < 5 || !data.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
            throw StatementImportException("Выберите файл PDF банковской выписки.")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
        try {
            PDDocument.load(data).use { document ->
                if (document.isEncrypted) {
                    throw StatementImportException("Защищённые паролем PDF пока не поддерживаются.")
                }
                if (document.numberOfPages !in 1..MAX_PAGES) {
                    throw StatementImportException("В выписке должно быть от 1 до 100 страниц.")
                }
                return ParsedPdfStatement(StatementParser.parse(extractNativePages(document)), hash)
            }
        } catch (error: StatementImportException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw error
        } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw StatementImportException("Защищённые паролем PDF пока не поддерживаются.")
        } catch (_: Exception) {
            // PDF exceptions may contain source text; never expose or log their messages.
            throw StatementImportException("Не удалось прочитать PDF. Выберите исходную текстовую выписку банка.")
        }
    }

    internal fun extractNativePages(document: PDDocument): List<PdfTextPage> {
        val stripper = PositionedTextStripper()
        stripper.getText(document)
        return stripper.pages
    }

    private class PositionedTextStripper : PDFTextStripper() {
        val pages = mutableListOf<PdfTextPage>()
        private var positions = mutableListOf<TextPosition>()
        private var textCount = 0

        init { sortByPosition = true; suppressDuplicateOverlappingText = true }

        override fun startPage(page: PDPage) {
            checkInterrupted()
            positions = mutableListOf()
            super.startPage(page)
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions == null) return
            textCount += textPositions.sumOf { it.unicode.length }
            if (textCount > MAX_TEXT_CHARS) throw StatementImportException("В PDF слишком много текста.")
            positions.addAll(textPositions)
        }

        override fun endPage(page: PDPage) {
            checkInterrupted()
            if (page.rotation != 0) {
                throw StatementImportException("Повернутые страницы пока не поддерживаются. Скачайте исходный PDF банка.")
            }
            pages.add(PdfTextPage(pages.size + 1, page.cropBox.width, page.cropBox.height, words(positions)))
            super.endPage(page)
        }

        private fun words(positions: List<TextPosition>): List<PdfWord> {
            val result = mutableListOf<PdfWord>()
            val lines = mutableListOf<MutableList<TextPosition>>()
            positions.sortedWith(compareBy<TextPosition> { it.yDirAdj }.thenBy { it.xDirAdj }).forEach { position ->
                if (position.dir != 0f) throw StatementImportException("Неподдерживаемое направление текста в PDF.")
                val line = lines.lastOrNull()
                if (line == null || abs(line.first().yDirAdj - position.yDirAdj) > 2f) {
                    lines.add(mutableListOf(position))
                } else line.add(position)
            }
            lines.forEach { line ->
                var text = StringBuilder()
                var start = 0f
                var end = 0f
                fun flush() {
                    if (text.isNotEmpty()) result.add(PdfWord(text.toString(), start, line.first().yDirAdj, end - start))
                    text = StringBuilder()
                }
                line.sortedBy { it.xDirAdj }.forEach { position ->
                    val value = position.unicode
                    val gap = position.xDirAdj - end
                    if (value.isBlank()) { flush(); return@forEach }
                    if (text.isNotEmpty() && gap > maxOf(1.5f, position.widthOfSpace * 0.5f)) flush()
                    if (text.isEmpty()) start = position.xDirAdj
                    text.append(value)
                    end = maxOf(position.xDirAdj + position.widthDirAdj, end)
                }
                flush()
            }
            return result
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MAX_PAGES = 100
        const val MAX_ROWS = 2000
        private const val MAX_TEXT_CHARS = 2_000_000
        private fun checkInterrupted() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PDF import cancelled")
        }
    }
}
