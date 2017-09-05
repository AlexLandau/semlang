package net.semlang.parser

import net.semlang.api.*
import net.semlang.api.Annotation
import java.io.StringWriter
import java.io.Writer

fun writeToString(module: ValidatedModule): String {
    val writer = StringWriter()
    write(module, writer)
    return writer.toString()
}

fun write(module: ValidatedModule, writer: Writer) {
    module.ownStructs.values.forEach { struct ->
        writeStruct(struct, writer)
    }
    module.ownInterfaces.values.forEach { interfac ->
        writeInterface(interfac, writer)
    }
    module.ownFunctions.values.forEach { function ->
        writeFunction(function, writer)
    }
}

private fun writeStruct(struct: Struct, writer: Writer) {
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

private fun writeInterface(interfac: Interface, writer: Writer) {
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

private fun writeFunction(function: ValidatedFunction, writer: Writer) {
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
                .append(annotation.name)
        if (annotation.value != null) {
            writer.append("(\"")
                    .append(annotation.value) // TODO: Will want to escape this
                    .append("\")")
        }
        writer.appendln()
    }
}

private val SINGLE_INDENTATION = "    "
private fun writeBlock(block: TypedBlock, indentationLevel: Int, writer: Writer) {
    val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
    for (assignment in block.assignments) {
        writer.append(indent)
                .append("let ")
                .append(assignment.name)
                .append(": ")
                .append(assignment.type.toString())
                .append(" = ")
        writeExpression(assignment.expression, indentationLevel, writer)
        writer.appendln()
    }
    writer.append(indent)
    writeExpression(block.returnedExpression, indentationLevel, writer)
    writer.appendln()
}

private fun writeExpression(expression: TypedExpression, indentationLevel: Int, writer: Writer) {
    val indent: String = SINGLE_INDENTATION.repeat(indentationLevel)
    when (expression) {
        is TypedExpression.Variable -> {
            writer.append(expression.name)
        }
        is TypedExpression.Literal -> {
            writer.append(expression.type.toString())
                    .append(".\"")
                    .append(expression.literal) // TODO: Might need escaping here?
                    .append("\"")
        }
        is TypedExpression.Follow -> {
            writeExpression(expression.expression, indentationLevel, writer)
            writer.append("->")
                    .append(expression.name)
        }
        is TypedExpression.NamedFunctionCall -> {
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
        is TypedExpression.NamedFunctionBinding -> {
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
        is TypedExpression.ExpressionFunctionCall -> {
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
        is TypedExpression.ExpressionFunctionBinding -> {
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
        is TypedExpression.IfThen -> {
            writer.append("if (")
            writeExpression(expression.condition, indentationLevel, writer)
            writer.appendln(" ) {")
            writeBlock(expression.thenBlock, indentationLevel + 1, writer)
            writer.append(indent)
                    .appendln("} else {")
            writeBlock(expression.elseBlock, indentationLevel + 1, writer)
            writer.append("}")
        }
        else -> error("Unhandled expression $expression of type ${expression.javaClass.name}")
    }
}
