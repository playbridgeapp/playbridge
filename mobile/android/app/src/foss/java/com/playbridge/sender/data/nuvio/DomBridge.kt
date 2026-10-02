package com.playbridge.sender.data.nuvio

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import kotlin.random.Random

internal class DomBridge(
    private val maxDocs: Int = MAX_DOCS,
    private val maxCumulativeHtmlBytes: Int = MAX_CUMULATIVE_HTML_BYTES,
    private val maxElements: Int = MAX_ELEMENTS,
    private val maxSelectorLength: Int = MAX_SELECTOR_LENGTH,
) {
    companion object {
        const val MAX_DOCS = 8
        const val MAX_CUMULATIVE_HTML_BYTES = 2 * 1024 * 1024 // 2 MiB
        const val MAX_ELEMENTS = 10_000
        const val MAX_SELECTOR_LENGTH = 512
        private val containsRegex = Regex(""":contains\(["']?([^"']{1,256})["']?\)""")
    }

    private val documentCache = mutableMapOf<String, Document>()
    private val elementCache = mutableMapOf<String, Element>()
    private var cumulativeHtmlBytes = 0
    private var idCounter = 0

    private fun validateSelector(raw: String?): String {
        val selector = raw?.trim() ?: ""
        if (selector.length > maxSelectorLength) {
            throw IllegalArgumentException("Selector length exceeds limit")
        }
        if (selector.contains(":matches", ignoreCase = true) || selector.contains("~=") ||
            selector.count { it == '(' } > 8) {
            throw IllegalArgumentException("Unsupported selector pseudo")
        }
        return if (selector.contains(":contains", ignoreCase = true)) {
            selector.replace(containsRegex, ":contains($1)")
        } else {
            selector
        }
    }

    fun register(runtime: QuickJs) {
        runtime.function("__cheerio_load") { args ->
            val html = args.getOrNull(0)?.toString() ?: ""
            if (documentCache.size >= maxDocs) {
                throw IllegalArgumentException("Document limit exceeded")
            }
            val htmlBytes = html.toByteArray(Charsets.UTF_8).size
            if (cumulativeHtmlBytes + htmlBytes > maxCumulativeHtmlBytes) {
                throw IllegalArgumentException("Cumulative HTML limit exceeded")
            }
            cumulativeHtmlBytes += htmlBytes

            val docId = "doc_${idCounter++}_${Random.nextInt(0, Int.MAX_VALUE)}"
            val document = Jsoup.parse(html)
            document.outputSettings().prettyPrint(false)
            if (document.getAllElements().size > maxElements) throw IllegalArgumentException("DOM node limit exceeded")
            documentCache[docId] = document
            docId
        }

        runtime.function("__cheerio_select") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val selector = validateSelector(args.getOrNull(1)?.toString())
            val doc = documentCache[docId] ?: return@function "[]"
            try {
                val elements = if (selector.isEmpty()) Elements() else doc.select(selector)
                if (elementCache.size + elements.size > maxElements) {
                    throw IllegalArgumentException("Element limit exceeded")
                }
                val ids = elements.mapIndexed { index, el ->
                    val id = "$docId:$index:${el.hashCode()}"
                    elementCache[id] = el
                    id
                }
                "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Exception) {
                "[]"
            }
        }

        runtime.function("__cheerio_find") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val selector = validateSelector(args.getOrNull(2)?.toString())
            val element = elementCache[elementId] ?: return@function "[]"
            try {
                val elements = element.select(selector)
                if (elementCache.size + elements.size > maxElements) {
                    throw IllegalArgumentException("Element limit exceeded")
                }
                val ids = elements.mapIndexed { index, el ->
                    val id = "$docId:find:$index:${el.hashCode()}"
                    elementCache[id] = el
                    id
                }
                "[" + ids.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Exception) {
                "[]"
            }
        }

        runtime.function("__cheerio_text") { args ->
            val elementIds = args.getOrNull(1)?.toString() ?: ""
            if (elementIds.length > 512 * 1024) throw IllegalArgumentException("Element identifier limit exceeded")
            val output = StringBuilder()
            for (id in elementIds.split(",")) {
                val text = elementCache[id]?.text() ?: continue
                if (output.length + text.length + 1 > maxCumulativeHtmlBytes) throw IllegalArgumentException("DOM text limit exceeded")
                if (output.isNotEmpty()) output.append(' ')
                output.append(text)
            }
            output.toString()
        }

        runtime.function("__cheerio_html") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            if (elementId.isEmpty()) {
                documentCache[docId]?.html() ?: ""
            } else {
                elementCache[elementId]?.html() ?: ""
            }
        }

        runtime.function("__cheerio_inner_html") { args ->
            val elementId = args.getOrNull(1)?.toString() ?: ""
            elementCache[elementId]?.html() ?: ""
        }

        runtime.function("__cheerio_attr") { args ->
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val attrName = args.getOrNull(2)?.toString() ?: ""
            if (attrName.length > 128) throw IllegalArgumentException("Attribute name exceeds limit")
            val value = elementCache[elementId]?.attr(attrName)
            if (value.isNullOrEmpty()) "__UNDEFINED__" else value
        }

        runtime.function("__cheerio_next") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val element = elementCache[elementId] ?: return@function "__NONE__"
            val next = element.nextElementSibling() ?: return@function "__NONE__"
            if (elementCache.size + 1 > maxElements) {
                throw IllegalArgumentException("Element limit exceeded")
            }
            val nextId = "$docId:next:${next.hashCode()}"
            elementCache[nextId] = next
            nextId
        }

        runtime.function("__cheerio_prev") { args ->
            val docId = args.getOrNull(0)?.toString() ?: ""
            val elementId = args.getOrNull(1)?.toString() ?: ""
            val element = elementCache[elementId] ?: return@function "__NONE__"
            val prev = element.previousElementSibling() ?: return@function "__NONE__"
            if (elementCache.size + 1 > maxElements) {
                throw IllegalArgumentException("Element limit exceeded")
            }
            val prevId = "$docId:prev:${prev.hashCode()}"
            elementCache[prevId] = prev
            prevId
        }
    }
}
