package net.semlang.interpreter

import java.util.*

sealed class ComplexLiteralNode {
    data class Literal(val contents: String): ComplexLiteralNode() {
        override fun toString(): String {
            return "'$contents'"
        }
    }
    data class SquareList(val contents: List<ComplexLiteralNode>): ComplexLiteralNode() {
        override fun toString(): String {
            return "[" + contents.joinToString(", ") + "]"
        }
    }
    data class CurlyList(val contents: List<CurlyNode>): ComplexLiteralNode()
    data class AngleList(val contents: List<ComplexLiteralNode>): ComplexLiteralNode()
}

sealed class CurlyNode {
    data class Singleton(val item: ComplexLiteralNode): CurlyNode()
    data class Pair(val key: String, val value: ComplexLiteralNode): CurlyNode()
}

sealed class ComplexLiteralParsingResult {
    data class Success(val node: ComplexLiteralNode): ComplexLiteralParsingResult() {
        override fun assumeSuccess(): ComplexLiteralNode {
            return node
        }
    }

    data class Failure(val errorMessage: String): ComplexLiteralParsingResult() {
        override fun assumeSuccess(): ComplexLiteralNode {
            error("Assumed success, but was failure: $errorMessage")
        }
    }

    abstract fun assumeSuccess(): ComplexLiteralNode
}

fun parseComplexLiteral(literal: String): ComplexLiteralParsingResult {
    return ComplexLiteralParser(literal).parse()
}

private class ComplexLiteralParser(val input: String) {
    private sealed class ParserState {
        class Initial: ParserState() {
            override fun addNode(node: ComplexLiteralNode) {
                contents.add(node)
            }

            val contents: MutableList<ComplexLiteralNode> = ArrayList()
        }
        object InBlockComment: ParserState() {
            override fun addNode(node: ComplexLiteralNode) {
                error("Comments should not be accepting nodes")
            }
        }

        object InLineComment: ParserState() {
            override fun addNode(node: ComplexLiteralNode) {
                error("Comments should not be accepting nodes")
            }
        }

        class SquareList: ParserState() {
            override fun addNode(node: ComplexLiteralNode) {
                builder.add(node)
            }

            val builder: MutableList<ComplexLiteralNode> = ArrayList()
            var justHadComma = true
        }

        class Literal: ParserState() {
            override fun addNode(node: ComplexLiteralNode) {
                error("Literals should not be accepting nodes")
            }

            val builder = StringBuilder()
            var justHadBackslash = false
        }

        abstract fun addNode(node: ComplexLiteralNode)
    }

    private var i = 0
    private val stateStack: Deque<ParserState> = ArrayDeque<ParserState>()

    fun parse(): ComplexLiteralParsingResult {
        if (i != 0 || !stateStack.isEmpty()) {
            error("Cannot reuse a ComplexLiteralParser")
        }
        stateStack.push(ParserState.Initial())
        try {
            while (i < input.length) {
                val curChar = input[i]

                val curState = stateStack.peek()

                when (curState) {
                    is ParserState.Initial -> {
                        when (curChar) {
                            '/' -> {
                                handleForwardSlashPossiblyStartingComment()
                            }
                            '[' -> {
                                stateStack.push(ParserState.SquareList())
                            }
                            // Ignore whitespace
                            ' ' -> {}
                            '\t' -> {}
                            '\n' -> {}
                            '\r' -> {}
                            else -> {
                                return parsingError("Unexpected character $curChar")
                            }
                        }
                    }
                    is ParserState.SquareList -> {
                        when (curChar) {
                            '/' -> {
                                handleForwardSlashPossiblyStartingComment()
                            }
                            '[' -> {
                                if (!curState.justHadComma) {
                                    return parsingError("Was expecting a comma or end-of-list before the next literal")
                                }
                                curState.justHadComma = false
                                stateStack.push(ParserState.SquareList())
                            }
                            ']' -> {
                                val completedState = curState
                                stateStack.pop()
                                val completedListNode = ComplexLiteralNode.SquareList(completedState.builder)
                                stateStack.peek().addNode(completedListNode)
                            }
                            '\'' -> {
                                if (!curState.justHadComma) {
                                    return parsingError("Was expecting a comma or end-of-list before the next literal")
                                }
                                curState.justHadComma = false
                                stateStack.push(ParserState.Literal())
                            }
                            ',' -> {
                                if (curState.justHadComma) {
                                    return parsingError("Unexpected comma")
                                }
                                curState.justHadComma = true
                            }
                            // Ignore whitespace
                            ' ' -> {}
                            '\t' -> {}
                            '\n' -> {}
                            '\r' -> {}
                            else -> {
                                return parsingError("Unexpected character $curChar")
                            }
                        }
                    }
                    is ParserState.Literal -> {
                        if (curState.justHadBackslash) {
                            curState.builder.append(applyBackslashRules(curChar))
                            curState.justHadBackslash = false
                        } else when (curChar) {
                            '\'' -> {
                                val completedState = curState
                                stateStack.pop()
                                val completedLiteralNode = ComplexLiteralNode.Literal(completedState.builder.toString())
                                stateStack.peek().addNode(completedLiteralNode)
                            }
                            '\\' -> {
                                curState.justHadBackslash = true
                            }
                            else -> {
                                curState.builder.append(curChar)
                            }
                        }
                    }
                    ParserState.InBlockComment -> {
                        if (curChar == '*') {
                            if (i + 1 < input.length && input[i + 1] == '/') {
                                stateStack.pop() // Leave the comment
                                i++
                            }
                        }
                    }
                    ParserState.InLineComment -> {
                        if (curChar == '\n') {
                            stateStack.pop()
                        }
                    }
                }

                i++
            }
        } catch (e: InternalParsingException) {
            return parsingError(e.message)
        }
        if (stateStack.size != 1) {
            return parsingError("Complex literal ends abruptly")
        }
        val endState = stateStack.pop() as? ParserState.Initial ?: error("Ended with non-initial state")
        val nodes = endState.contents
        if (nodes.isEmpty()) {
            return parsingError("Complex literal had no content")
        } else if (nodes.size > 1) {
            return parsingError("Complex literal contained multiple values outside of a list or structure")
        }
        return ComplexLiteralParsingResult.Success(nodes[0])
    }

    private fun applyBackslashRules(curChar: Char): Char {
        // TODO: Find the other function that already does this for strings and use that
        if (curChar in setOf('n', 't', 'r')) {
            TODO()
        }
        return curChar
    }

    private fun handleForwardSlashPossiblyStartingComment() {
        if (i + 1 >= input.length) {
            throw InternalParsingException("Extra '/' at end of complex literal")
        }
        val nextChar = input[i + 1]
        if (nextChar == '/') {
            stateStack.push(ParserState.InLineComment)
            i++
        } else if (nextChar == '*') {
            stateStack.push(ParserState.InBlockComment)
            i++
        } else {
            throw InternalParsingException("Unexpected '/' not part of comment declaration")
        }
    }

    private fun parsingError(errorMessage: String): ComplexLiteralParsingResult {
        return ComplexLiteralParsingResult.Failure(errorMessage)
    }
}

private class InternalParsingException(override val message: String): Exception(message)
