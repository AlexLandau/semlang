package net.semlang.sem2.translate

import net.semlang.sem2.api.S2Block
import net.semlang.sem2.api.S2Expression
import net.semlang.sem2.api.S2Statement
import java.io.StringWriter
import java.io.Writer

// TODO: Move this to another module, and test

fun writeSem2(statement: S2Statement): String {
    val writer = StringWriter()
    Sem2Writer(writer).write(statement)
    return writer.toString()
}

private class Sem2Writer(val writer: Writer) {
    fun write(statement: S2Statement) {
        val unused = when (statement) {
            is S2Statement.Assignment -> {
                writer.append("let ").append(statement.name)
                if (statement.type != null) {
                    writer.append(": ").append(statement.type.toString()) // TODO: ???
                }
                writer.append(" = ")
                write(statement.expression)
            }
            is S2Statement.Bare -> {
                write(statement.expression)
            }
            is S2Statement.Return -> {
                writer.append("return ")
                write(statement.expression)
            }
            is S2Statement.WhileLoop -> TODO()
        }
    }

    fun write(expression: S2Expression) {
        val unused = when (expression) {
            is S2Expression.RawId -> {
                writer.append(expression.name)
            }
            is S2Expression.DotAccess -> {
                write(expression.subexpression)
                writer.append(".").append(expression.name)
            }
            is S2Expression.IfThen -> {
                // TODO: indent
                writer.append("if (")
                write(expression.condition)
                writer.append(") {\n")
                write(expression.thenBlock)
                writer.append("} else {\n")
                write(expression.elseBlock)
                writer.append("}")
            }
            is S2Expression.FunctionCall -> {
                write(expression.expression)
                writer.append("(")
                for (argument in expression.arguments) {
                    write(argument)
                    // TODO: Fix up here
                    writer.append(", ")
                }
                writer.append(")")
            }
            is S2Expression.Literal -> {
                writer.append(expression.type.toString()) // TODO: ?
                writer.append(".\"")
                writer.append(expression.literal)
                writer.append("\"")
            }
            is S2Expression.IntegerLiteral -> {
                writer.append(expression.literal)
            }
            is S2Expression.ListLiteral -> {
                writer.append("[")
                for (item in expression.contents) {
                    write(item)
                    // TODO: Fix up here
                    writer.append(", ")
                }
                writer.append("]<")
                writer.append(expression.chosenParameter.toString())
                writer.append(">")
            }
            is S2Expression.FunctionBinding -> {
                write(expression.expression)
                writer.append("|(")
                for (binding in expression.bindings) {
                    if (binding == null) {
                        writer.append("_")
                    } else {
                        write(binding)
                    }
                    // TODO: Fix up here
                    writer.append(", ")
                }
                writer.append(")")
            }
            is S2Expression.Follow -> {
                write(expression.structureExpression)
                writer.append("->")
                writer.append(expression.name)
            }
            is S2Expression.InlineFunction -> TODO()
            is S2Expression.PlusOp -> TODO()
            is S2Expression.MinusOp -> TODO()
            is S2Expression.TimesOp -> TODO()
            is S2Expression.EqualsOp -> TODO()
            is S2Expression.NotEqualsOp -> TODO()
            is S2Expression.LessThanOp -> TODO()
            is S2Expression.GreaterThanOp -> TODO()
            is S2Expression.DotAssignOp -> TODO()
            is S2Expression.GetOp -> {
                write(expression.subject)
                writer.append("[")
                for (argument in expression.arguments) {
                    write(argument)
                    // TODO: Fix up here
                    writer.append(", ")
                }
                writer.append("]")
            }
            is S2Expression.AndOp -> TODO()
            is S2Expression.OrOp -> TODO()
        }
    }

    fun write(block: S2Block) {
        for (statement in block.statements) {
            // TODO: indent
            writer.append("  ")
            write(statement)
            writer.append("\n")
        }
    }
}