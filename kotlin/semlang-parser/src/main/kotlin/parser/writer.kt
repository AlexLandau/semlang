package net.semlang.parser

import net.semlang.api.*
import net.semlang.api.Annotation
import net.semlang.api.Function
import net.semlang.transforms.invalidate
import java.io.StringWriter
import java.io.Writer

fun writeToString(module: ValidatedModule): String {
    val writer = StringWriter()
    write(module, writer)
    return writer.toString()
}

// TODO: Right now deterministicMode is defined just for fake0 versioning
fun writeToString(context: RawContext, deterministicMode: Boolean = false): String {
    val writer = StringWriter()
    write(context, writer, deterministicMode)
    return writer.toString()
}

fun write(module: ValidatedModule, writer: Writer) {
    val context = invalidate(module)
    write(context, writer)
}

// TODO: Right now deterministicMode is defined just for fake0 versioning
fun write(context: RawContext, writer: Writer, deterministicMode: Boolean = false) {
    Sem1Writer(writer, deterministicMode).write(context)
}

private class Sem1Writer(val writer: Writer, val deterministicMode: Boolean) {
    fun write(context: RawContext) {
        fun <T : HasId> maybeSort(list: List<T>): List<T> {
            return if (deterministicMode) {
                list.sortedWith(HasEntityIdComparator)
            } else {
                list
            }
        }
        for (struct in maybeSort(context.structs)) {
            write(struct)
        }
        for (union in maybeSort(context.unions)) {
            write(union)
        }
        for (function in maybeSort(context.functions)) {
            write(function)
        }
    }

    private fun write(struct: UnvalidatedStruct) {
        val newline = if (deterministicMode) "\n" else System.lineSeparator()
        writeAnnotations(struct.annotations)
        writer.append("struct ")
            .append(struct.id.toString())
        if (struct.typeParameters.isNotEmpty()) {
            writer.append("<")
                .append(struct.typeParameters.joinToString(", "))
                .append(">")
        }
        writer.append(" {$newline")
        for (member in struct.members) {
            writer.append(SINGLE_INDENTATION)
                .append(member.name)
                .append(": ")
                .append(member.type.toString() + newline)
        }
        val requires = struct.requires
        if (requires != null) {
            writer.append(SINGLE_INDENTATION)
                .append("requires {$newline")
            this.write(requires, 2)
            writer.append(SINGLE_INDENTATION)
                .append("}$newline")
        }
        writer.append("}$newline$newline")
    }

    private fun write(union: UnvalidatedUnion) {
        val newline = if (deterministicMode) "\n" else System.lineSeparator()
        writeAnnotations(union.annotations)
        writer.append("union ")
            .append(union.id.toString())
        if (union.typeParameters.isNotEmpty()) {
            writer.append("<")
                .append(union.typeParameters.joinToString(", "))
                .append(">")
        }
        writer.append(" {$newline")
        for (option in union.options) {
            val type = option.type
            if (type == null) {
                writer.append(SINGLE_INDENTATION)
                    .append(option.name + newline)
            } else {
                writer.append(SINGLE_INDENTATION)
                    .append(option.name)
                    .append(": ")
                    .append(type.toString() + newline)
            }
        }
        writer.append("}$newline$newline")
    }

    private fun write(function: Function) {
        val newline = if (deterministicMode) "\n" else System.lineSeparator()
        writeAnnotations(function.annotations)
        writer.append("function ")
            .append(function.id.toString())
        if (function.typeParameters.isNotEmpty()) {
            writer.append("<")
                .append(function.typeParameters.joinToString(", "))
                .append(">")
        }
        writer.append("(")
            .append(function.arguments.joinToString { argument ->
                argument.name + ": " + argument.type.toString()
            })
            .append("): ")
            .append(function.returnType.toString())

        writer.append(" {$newline")
        this.write(function.block, 1)
        writer.append("}$newline$newline")
    }

    private fun writeAnnotations(annotations: List<Annotation>) {
        val newline = if (deterministicMode) "\n" else System.lineSeparator()
        for (annotation in annotations) {
            writer.append("@")
                .append(annotation.name.toString())
            if (annotation.values.isNotEmpty()) {
                writer.append("(")
                writeAnnotationArguments(annotation.values)
                writer.append(")")
            }
            writer.append(newline)
        }
    }

    private fun writeAnnotationArguments(annotationArgs: List<AnnotationArgument>) {
        var isFirst = true
        for (arg in annotationArgs) {
            if (!isFirst) {
                writer.append(", ")
            }
            isFirst = false
            val unused = when (arg) {
                is AnnotationArgument.Literal -> {
                    writer.append("\"")
                        .append(escapeLiteralContents(arg.value))
                        .append("\"")
                }
                is AnnotationArgument.List -> {
                    writer.append("[")
                    writeAnnotationArguments(arg.values)
                    writer.append("]")
                }
            }
        }
    }

    private val SINGLE_INDENTATION = "    "
    private fun write(block: Block, indentationLevel: Int) {
        val newline = if (deterministicMode) "\n" else System.lineSeparator()
        val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
        for (statement in block.statements) {
            writer.append(indent)
            this.write(statement, indentationLevel)
            writer.append(newline)
        }
    }

    // Note: This doesn't include indentation or newlines
    private fun write(statement: Statement, indentationLevel: Int) {
        val unused: Any? = when (statement) {
            is Statement.Assignment -> {
                writer.append("let ")
                    .append(statement.name)
                if (statement.type != null && !deterministicMode) {
                    writer.append(": ")
                        .append(statement.type.toString())
                }
                writer.append(" = ")
                write(statement.expression, indentationLevel)
            }
            is Statement.Bare -> {
                write(statement.expression, indentationLevel)
            }
        }
    }

    private fun write(
        expression: Expression,
        indentationLevel: Int
    ) {
        val newline = if (deterministicMode) "\n" else System.lineSeparator()
        val unused = when (expression) {
            is Expression.Variable -> {
                writer.append(expression.name)
            }
            is Expression.Literal -> {
                writer.append(expression.type.toString())
                    .append(".\"")
                    .append(escapeLiteralContents(expression.literal)) // TODO: Might need escaping here?
                    .append("\"")
            }
            is Expression.ListLiteral -> {
                writer.append("[")
                var first = true
                for (item in expression.contents) {
                    if (!first) {
                        writer.append(", ")
                    }
                    first = false
                    write(item, indentationLevel)
                }
                writer.append("]<")
                    .append(expression.chosenParameter.toString())
                    .append(">")
            }
            is Expression.Follow -> {
                write(expression.structureExpression, indentationLevel)
                writer.append("->")
                    .append(expression.name)
            }
            is Expression.NamedFunctionCall -> {
                writer.append(expression.functionRef.toString())
                if (expression.chosenParameters.isNotEmpty()) {
                    writer.append("<")
                        .append(expression.chosenParameters.joinToString(", "))
                        .append(">")
                }
                writer.append("(")
                var first = true
                for (argument in expression.arguments) {
                    if (!first) {
                        writer.append(", ")
                    }
                    first = false
                    write(argument, indentationLevel)
                }
                writer.append(")")
            }
            is Expression.NamedFunctionBinding -> {
                writer.append(expression.functionRef.toString())
                if (expression.chosenParameters.isNotEmpty()) {
                    writer.append("<")
                        .append(expression.chosenParameters.map { if (it == null) "_" else it }.joinToString(", "))
                        .append(">")
                }
                writer.append("|(")
                var first = true
                for (binding in expression.bindings) {
                    if (!first) {
                        writer.append(", ")
                    }
                    first = false
                    if (binding == null) {
                        writer.append("_")
                    } else {
                        write(binding, indentationLevel)
                    }
                }
                writer.append(")")
            }
            is Expression.ExpressionFunctionCall -> {
                write(expression.functionExpression, indentationLevel)
                if (expression.chosenParameters.isNotEmpty()) {
                    writer.append("<")
                        .append(expression.chosenParameters.joinToString(", "))
                        .append(">")
                }
                writer.append("(")
                var first = true
                for (argument in expression.arguments) {
                    if (!first) {
                        writer.append(", ")
                    }
                    first = false
                    write(argument, indentationLevel)
                }
                writer.append(")")
            }
            is Expression.ExpressionFunctionBinding -> {
                write(expression.functionExpression, indentationLevel)
                if (expression.chosenParameters.isNotEmpty()) {
                    writer.append("<")
                        .append(expression.chosenParameters.map { if (it == null) "_" else it }.joinToString(", "))
                        .append(">")
                }
                writer.append("|(")
                var first = true
                for (binding in expression.bindings) {
                    if (!first) {
                        writer.append(", ")
                    }
                    first = false
                    if (binding == null) {
                        writer.append("_")
                    } else {
                        write(binding, indentationLevel)
                    }
                }
                writer.append(")")
            }
            is Expression.IfThen -> {
                val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
                writer.append("if (")
                write(expression.condition, indentationLevel)
                writer.append(") {$newline")
                this.write(expression.thenBlock, indentationLevel + 1)
                writer.append(indent)
                    .append("} else {$newline")
                this.write(expression.elseBlock, indentationLevel + 1)
                writer.append(indent).append("}")
            }
            is Expression.InlineFunction -> {
                val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
                writer.append("function(")
                writer.append(expression.arguments.joinToString { argument ->
                    argument.name + ": " + argument.type.toString()
                })
                writer.append("): ")
                writer.append(expression.returnType.toString())
                writer.append(" {$newline")
                this.write(expression.block, indentationLevel + 1)
                writer.append(indent).append("}")
            }
        }
    }
}

fun escapeLiteralContents(literal: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < literal.length) {
        val c = literal[i]
        when (c) {
            '\\' -> sb.append("\\\\")
            '\"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

private object HasEntityIdComparator : Comparator<HasId> {
    override fun compare(hasId1: HasId, hasId2: HasId): Int {
        val strings1 = hasId1.id.namespacedName
        val strings2 = hasId2.id.namespacedName
        var i = 0
        while (true) {
            // When they are otherwise the same, shorter precedes longer
            val done1 = i >= strings1.size
            val done2 = i >= strings2.size
            if (done1) {
                if (done2) {
                    return 0
                } else {
                    return -1
                }
            }
            if (done2) {
                return 1
            }
            val stringComparison = strings1[i].compareTo(strings2[i])
            if (stringComparison != 0) {
                return stringComparison
            }
            i++
        }
    }
}