package net.semlang.parser.test

import net.semlang.api.parser.*
import java.io.File
import java.util.regex.Pattern

data class ErrorFile(val lines: List<String>, val errors: Set<Issue>) {
    fun getText(): String {
        return lines.joinToString("\n")
    }
}

fun loadErrorFile(file: File): ErrorFile {
    val contents = file.readText()
    return parseErrorFileText(contents, file.absolutePath)
}

val ErrorPattern = Pattern.compile("^( *)(~+) *(.+)$")

fun parseErrorFileText(contents: String, documentUri: String): ErrorFile {
    val allLines = contents.lineSequence().toList()
    val contentLines = ArrayList<String>()
    val errors = HashSet<Issue>()
    var lineNumber = 0
    var rawIndexAtLastLineStart = 0
    var rawIndexAtNextLineStart = 0
    for (line in allLines) {
        val matcher = ErrorPattern.matcher(line)
        if (matcher.matches()) {
            val initialSpaceCount = matcher.group(1).length
            val tildeCount = matcher.group(2).length
            val errorMessage = matcher.group(3)

            val startColumn = initialSpaceCount
            val endColumn = initialSpaceCount + tildeCount
            val location = Location(documentUri, Range(
                    Position(lineNumber, startColumn, rawIndexAtLastLineStart + startColumn),
                    Position(lineNumber, endColumn, rawIndexAtLastLineStart + endColumn - 1))
            )

            errors.add(Issue(errorMessage, location, IssueLevel.ERROR))
        } else {
            contentLines.add(line)
            lineNumber++
            rawIndexAtLastLineStart = rawIndexAtNextLineStart
            rawIndexAtNextLineStart += line.length + 1 // +1 for \n
        }
    }
    removeTrailingEmptyLines(contentLines)
    return ErrorFile(contentLines, errors)
}

fun removeTrailingEmptyLines(lines: ArrayList<String>) {
    while (!lines.isEmpty() && lines.last().isEmpty()) {
        lines.removeAt(lines.size - 1)
    }
}

// TODO: This first iteration doesn't support multi-line errors
fun writeErrorFileText(errorFile: ErrorFile): String {
    val sb = StringBuilder()
    val errorsByLine = errorFile.errors.groupBy {
        // Positions use 1-indexed line numbers; switch to 0-indexed here
        it.location!!.range.start.lineNumber - 1
    }
    errorFile.lines.forEachIndexed { index, line ->
        sb.appendln(line)
        val errorsInLine = errorsByLine[index]
        if (errorsInLine != null) {
            for (error in errorsInLine) {
                sb.appendln(writeErrorLine(error))
            }
        }
    }
    return sb.toString()
}

fun writeErrorLine(error: Issue): String {
    val location = error.location!!
    val startCol = location.range.start.column
    val endCol = location.range.end.column
    return "".padEnd(startCol, ' ') +
            "".padEnd(endCol - startCol, '~') +
            " " + error.message
}
