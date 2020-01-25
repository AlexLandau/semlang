package net.semlang.sem2.parser

import net.semlang.sem2.api.*
import java.io.StringWriter
import java.io.Writer

fun writeToString(context: S2Context): String {
    val writer = StringWriter()
    Sem2Writer(writer).write(context)
    return writer.toString()
}

fun writeToString(statement: S2Statement): String {
    val writer = StringWriter()
    Sem2Writer(writer).write(statement, 0)
    return writer.toString()
}

// TODO: Proper indentation, like the sem1 writer
// TODO: Make output prettier
private class Sem2Writer(val writer: Writer) {
    private val SINGLE_INDENTATION = "    "
    private val newline = "\n"

    fun write(context: S2Context) {
        for (struct in context.structs) {
            write(struct)
        }
        for (union in context.unions) {
            write(union)
        }
        for (function in context.functions) {
            write(function)
        }
    }

    private fun write(function: S2Function) {
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

    private fun write(union: S2Union) {
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

    private fun write(struct: S2Struct) {
        val newline = "\n"
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

    // Note: This doesn't include indentation or newlines
    fun write(statement: S2Statement, indentationLevel: Int) {
        val unused: Any? = when (statement) {
            is S2Statement.Assignment -> {
                writer.append("let ")
                    .append(statement.name)
                if (statement.type != null) {
                    writer.append(": ")
                        .append(statement.type.toString())
                }
                writer.append(" = ")
                write(statement.expression, indentationLevel)
            }
            is S2Statement.Bare -> {
                write(statement.expression, indentationLevel)
            }
            is S2Statement.WhileLoop -> {
                writer.append("while (")
                write(statement.conditionExpression, indentationLevel)
                writer.append(") {\n")
                write(statement.actionBlock, indentationLevel + 1)
                writer.append("}")
            }
        }
    }

    private fun write(expression: S2Expression, indentationLevel: Int) {
        val unused: Any? = when (expression) {
            is S2Expression.RawId -> {
                writer.append(expression.name)
            }
            is S2Expression.DotAccess -> {
                write(expression.subexpression, indentationLevel)
                writer.append(".").append(expression.name)
            }
            is S2Expression.Literal -> {
                writer.append(expression.type.toString())
                    .append(".\"")
                    .append(escapeLiteralContents(expression.literal)) // TODO: Might need escaping here?
                    .append("\"")
            }
            is S2Expression.ListLiteral -> {
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
            is S2Expression.Follow -> {
                write(expression.structureExpression, indentationLevel)
                writer.append("->")
                    .append(expression.name)
            }
            is S2Expression.FunctionCall -> {
                write(expression.expression, indentationLevel)
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
            is S2Expression.FunctionBinding -> {
                write(expression.expression, indentationLevel)
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
            is S2Expression.IfThen -> {
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
            is S2Expression.InlineFunction -> {
                val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
                if (expression.returnType != null) {
                    writer.append("function(")
                    writer.append(expression.arguments.joinToString { argument ->
                        argument.name + ": " + argument.type.toString()
                    })
                    writer.append("): ")
                    writer.append(expression.returnType.toString())
                    writer.append(" {$newline")
                    this.write(expression.block, indentationLevel + 1)
                    writer.append(indent).append("}")
                } else {
                    writer.append("{ ")
                    writer.append(expression.arguments.joinToString { argument ->
                        argument.name + ": " + argument.type.toString()
                    })
                    if (expression.arguments.isEmpty()) {
                        writer.append(newline)
                    } else {
                        writer.append(" ->$newline")
                    }
                    this.write(expression.block, indentationLevel + 1)
                    writer.append(indent).append("}")
                }
            }
            is S2Expression.IntegerLiteral -> {
                writer.append(expression.literal)
            }
            is S2Expression.PlusOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" + ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.MinusOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" - ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.TimesOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" * ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.EqualsOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" == ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.NotEqualsOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" != ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.LessThanOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" < ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.GreaterThanOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" > ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.DotAssignOp -> {
                // TODO: Parens?
                write(expression.left, indentationLevel)
                writer.append(".= ")
                write(expression.right, indentationLevel)
            }
            is S2Expression.GetOp -> {
                write(expression.subject, indentationLevel)
                writer.append("[")
                for (argument in expression.arguments) {
                    write(argument, indentationLevel)
                    // TODO: Fix up here
                    writer.append(", ")
                }
                writer.append("]")
            }
            is S2Expression.AndOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" && ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
            is S2Expression.OrOp -> {
                writer.append("(")
                write(expression.left, indentationLevel)
                writer.append(" || ")
                write(expression.right, indentationLevel)
                writer.append(")")
            }
        }
    }

    fun write(block: S2Block, indentationLevel: Int) {
        val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
        for (statement in block.statements) {
            writer.append(indent)
            this.write(statement, indentationLevel)
            writer.append(newline)
        }
    }

    private fun writeAnnotations(annotations: List<S2Annotation>) {
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

    private fun writeAnnotationArguments(annotationArgs: List<S2AnnotationArgument>) {
        var isFirst = true
        for (arg in annotationArgs) {
            if (!isFirst) {
                writer.append(", ")
            }
            isFirst = false
            val unused = when (arg) {
                is S2AnnotationArgument.Literal -> {
                    writer.append("\"")
                        .append(escapeLiteralContents(arg.value))
                        .append("\"")
                }
                is S2AnnotationArgument.List -> {
                    writer.append("[")
                    writeAnnotationArguments(arg.values)
                    writer.append("]")
                }
            }
        }
    }
}

// TODO: Deduplicate from sem1 writer
private fun escapeLiteralContents(literal: String): String {
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
