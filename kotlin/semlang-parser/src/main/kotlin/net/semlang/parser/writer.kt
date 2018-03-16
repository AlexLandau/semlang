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

fun writeToString(context: RawContext): String {
    val writer = StringWriter()
    write(context, writer)
    return writer.toString()
}

fun write(module: ValidatedModule, writer: Writer) {
    // TODO: Just invalidate, then use the other code path
    val context = invalidate(module)
    write(context, writer)
}

fun write(context: RawContext, writer: Writer) {
    context.structs.forEach { struct ->
        writeStruct(struct, writer)
    }
    context.interfaces.forEach { interfac ->
        writeInterface(interfac, writer)
    }
    context.functions.forEach { function ->
        writeFunction(function, writer)
    }
}

private fun writeStruct(struct: UnvalidatedStruct, writer: Writer) {
    writeAnnotations(struct.annotations, writer)
    writer.append("struct ")
            .append(struct.id.toString())
    if (struct.typeParameters.isNotEmpty()) {
        writer.append("<")
                .append(struct.typeParameters.joinToString(", "))
                .append(">")
    }
    writer.appendln(" {")
    for (member in struct.members) {
        writer.append(SINGLE_INDENTATION)
                .append(member.name)
                .append(": ")
                .appendln(member.type.toString())
    }
    val requires = struct.requires
    if (requires != null) {
        writer.append(SINGLE_INDENTATION)
                .appendln("requires {")
        writeBlock(requires, 2, writer)
        writer.append(SINGLE_INDENTATION)
                .appendln("}")
    }
    writer.appendln("}")
            .appendln()
}

private fun writeInterface(interfac: UnvalidatedInterface, writer: Writer) {
    writeAnnotations(interfac.annotations, writer)
    writer.append("interface ")
            .append(interfac.id.toString())
    if (interfac.typeParameters.isNotEmpty()) {
        writer.append("<")
                .append(interfac.typeParameters.joinToString(", "))
                .append(">")
    }
    writer.appendln(" {")
    for (method in interfac.methods) {
        writer.append(SINGLE_INDENTATION)
                .append(method.name)
                .append("(")
                .append(method.arguments.joinToString { argument ->
                    argument.name + ": " + argument.type.toString()
                })
                .append("): ")
                .appendln(method.returnType.toString())
    }
    writer.appendln("}")
            .appendln()
}

private fun writeFunction(function: Function, writer: Writer) {
    writeAnnotations(function.annotations, writer)
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

    writer.appendln(" {")
    writeBlock(function.block, 1, writer)
    writer.appendln("}")
            .appendln()
}

private fun writeAnnotations(annotations: List<Annotation>, writer: Writer) {
    for (annotation in annotations) {
        writer.append("@")
                .append(annotation.name.toString())
        if (annotation.values.isNotEmpty()) {
            writer.append("(")
            writeAnnotationArguments(annotation.values, writer)
            writer.append(")")
        }
        writer.appendln()
    }
}

private fun writeAnnotationArguments(annotationArgs: List<AnnotationArgument>, writer: Writer) {
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
                writeAnnotationArguments(arg.values, writer)
                writer.append("]")
            }
        }
    }
}

private val SINGLE_INDENTATION = "    "
private fun writeBlock(block: Block, indentationLevel: Int, writer: Writer) {
    val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
    for (assignment in block.assignments) {
        writer.append(indent)
                .append("let ")
                .append(assignment.name)
        if (assignment.type != null) {
            writer.append(": ")
                    .append(assignment.type.toString())
        }
        writer.append(" = ")
        writeExpression(assignment.expression, indentationLevel, writer)
        writer.appendln()
    }
    writer.append(indent)
    writeExpression(block.returnedExpression, indentationLevel, writer)
    writer.appendln()
}

private fun writeExpression(expression: Expression, indentationLevel: Int, writer: Writer) {
    when (expression) {
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
                writeExpression(item, indentationLevel, writer)
            }
            writer.append("]<")
                    .append(expression.chosenParameter.toString())
                    .append(">")
        }
        is Expression.Follow -> {
            writeExpression(expression.structureExpression, indentationLevel, writer)
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
                writeExpression(argument, indentationLevel, writer)
            }
            writer.append(")")
        }
        is Expression.NamedFunctionBinding -> {
            writer.append(expression.functionRef.toString())
            if (expression.chosenParameters.isNotEmpty()) {
                writer.append("<")
                        .append(expression.chosenParameters.joinToString(", "))
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
                    writeExpression(binding, indentationLevel, writer)
                }
            }
            writer.append(")")
        }
        is Expression.ExpressionFunctionCall -> {
            writeExpression(expression.functionExpression, indentationLevel, writer)
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
                writeExpression(argument, indentationLevel, writer)
            }
            writer.append(")")
        }
        is Expression.ExpressionFunctionBinding -> {
            writeExpression(expression.functionExpression, indentationLevel, writer)
            if (expression.chosenParameters.isNotEmpty()) {
                writer.append("<")
                        .append(expression.chosenParameters.joinToString(", "))
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
                    writeExpression(binding, indentationLevel, writer)
                }
            }
            writer.append(")")
        }
        is Expression.IfThen -> {
            val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
            writer.append("if (")
            writeExpression(expression.condition, indentationLevel, writer)
            writer.appendln(" ) {")
            writeBlock(expression.thenBlock, indentationLevel + 1, writer)
            writer.append(indent)
                    .appendln("} else {")
            writeBlock(expression.elseBlock, indentationLevel + 1, writer)
            writer.append("}")
        }
        is Expression.InlineFunction -> {
            writer.append("function(")
            writer.append(expression.arguments.joinToString { argument ->
                argument.name + ": " + argument.type.toString()
            })
            writer.appendln(") {")
            writeBlock(expression.block, indentationLevel + 1, writer)
            writer.append("}")
        }
        else -> error("Unhandled expression $expression of type ${expression.javaClass.name}")
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
