package net.semlang.sem2.parser

import net.semlang.internal.test.getAllStandaloneCompilableFiles
import net.semlang.sem2.api.*
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class Sem2WriterTest(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return File("../../sem2-corpus").listFiles().map { file ->
                arrayOf<Any?>(file)
            }
        }
    }

    @Test
    fun testParseWriteParseEquality() {
        val initiallyParsed = parseFile(file).assumeSuccess()
        val writtenToString = writeToString(initiallyParsed)
        try {
            val reparsed = parseString(writtenToString, "").assumeSuccess()
            assertRawS2ContextsEqual(initiallyParsed, reparsed)
        } catch (t: Throwable) {
            throw AssertionError("Error while reparsing; written version was: $writtenToString", t)
        }
    }
}

@RunWith(Parameterized::class)
class Sem1CorpusSem2WriterTest(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getAllStandaloneCompilableFiles()
//            return File("../../sem2-corpus").listFiles().map { file ->
//                arrayOf<Any?>(file)
//            }
        }
    }

    @Test
    fun testParseWriteParseEquality() {
        val initiallyParsed = parseFile(file).assumeSuccess()
        val writtenToString = writeToString(initiallyParsed)
        try {
            val reparsed = parseString(writtenToString, "").assumeSuccess()
            assertRawS2ContextsEqual(initiallyParsed, reparsed)
        } catch (t: Throwable) {
            throw AssertionError("Error while reparsing; written version was: $writtenToString", t)
        }
    }
}

fun assertRawS2ContextsEqual(expected: S2Context, actual: S2Context) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.functions.map(::stripLocations), actual.functions.map(::stripLocations))
    Assert.assertEquals(expected.structs.map(::stripLocations), actual.structs.map(::stripLocations))
    Assert.assertEquals(expected.unions.map(::stripLocations), actual.unions.map(::stripLocations))
}

fun stripLocations(function: S2Function): S2Function {
    return S2Function(
        function.id,
        function.typeParameters,
        function.arguments.map(::stripLocations),
        stripLocations(function.returnType),
        stripLocations(function.block),
        function.annotations
    )
}

fun stripLocations(argument: S2Argument): S2Argument {
    return S2Argument(
        argument.name,
        stripLocations(argument.type)
    )
}

fun stripLocations(type: S2Type): S2Type {
    return when (type) {
        is S2Type.FunctionType -> {
            S2Type.FunctionType(
                type.isReference,
                type.typeParameters,
                type.argTypes.map(::stripLocations),
                stripLocations(type.outputType)
            )
        }
        is S2Type.NamedType -> {
            S2Type.NamedType(
                type.ref,
                type.isReference,
                type.parameters.map(::stripLocations)
            )
        }
    }
}

fun stripLocations(block: S2Block): S2Block {
    return S2Block(block.statements.map(::stripLocations))
}

fun stripLocations(statement: S2Statement): S2Statement {
    return when (statement) {
        is S2Statement.Assignment -> {
            S2Statement.Assignment(
                statement.name,
                statement.type?.let(::stripLocations),
                stripLocations(statement.expression)
            )
        }
        is S2Statement.Bare -> {
            S2Statement.Bare(
                stripLocations(statement.expression)
            )
        }
        is S2Statement.WhileLoop -> {
            S2Statement.WhileLoop(
                stripLocations(statement.conditionExpression),
                stripLocations(statement.actionBlock)
            )
        }
    }
}

fun stripLocations(expression: S2Expression): S2Expression {
    return when (expression) {
        is S2Expression.RawId -> {
            S2Expression.RawId(expression.name)
        }
        is S2Expression.DotAccess -> {
            S2Expression.DotAccess(
                stripLocations(expression.subexpression),
                expression.name
            )
        }
        is S2Expression.IfThen -> {
            S2Expression.IfThen(
                stripLocations(expression.condition),
                stripLocations(expression.thenBlock),
                stripLocations(expression.elseBlock)
            )
        }
        is S2Expression.FunctionCall -> {
            S2Expression.FunctionCall(
                stripLocations(expression.expression),
                expression.arguments.map(::stripLocations),
                expression.chosenParameters.map(::stripLocations)
            )
        }
        is S2Expression.Literal -> {
            S2Expression.Literal(
                stripLocations(expression.type),
                expression.literal
            )
        }
        is S2Expression.IntegerLiteral -> {
            S2Expression.IntegerLiteral(expression.literal)
        }
        is S2Expression.ListLiteral -> {
            S2Expression.ListLiteral(
                expression.contents.map(::stripLocations),
                stripLocations(expression.chosenParameter)
            )
        }
        is S2Expression.FunctionBinding -> {
            S2Expression.FunctionBinding(
                stripLocations(expression.expression),
                expression.bindings.map { if (it == null) null else stripLocations(it) },
                expression.chosenParameters.map { if (it == null) null else stripLocations(it) }
            )
        }
        is S2Expression.Follow -> {
            S2Expression.Follow(
                stripLocations(expression.structureExpression),
                expression.name
            )
        }
        is S2Expression.InlineFunction -> {
            S2Expression.InlineFunction(
                expression.arguments.map(::stripLocations),
                expression.returnType?.let(::stripLocations),
                stripLocations(expression.block)
            )
        }
        is S2Expression.PlusOp -> {
            S2Expression.PlusOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.MinusOp -> {
            S2Expression.MinusOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.TimesOp -> {
            S2Expression.TimesOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.EqualsOp -> {
            S2Expression.EqualsOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.NotEqualsOp -> {
            S2Expression.NotEqualsOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.LessThanOp -> {
            S2Expression.LessThanOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.GreaterThanOp -> {
            S2Expression.GreaterThanOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.DotAssignOp -> {
            S2Expression.DotAssignOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.GetOp -> {
            S2Expression.GetOp(
                stripLocations(expression.subject),
                expression.arguments.map(::stripLocations)
            )
        }
        is S2Expression.AndOp -> {
            S2Expression.AndOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
        is S2Expression.OrOp -> {
            S2Expression.OrOp(
                stripLocations(expression.left),
                stripLocations(expression.right)
            )
        }
    }
}

fun stripLocations(struct: S2Struct): S2Struct {
    return S2Struct(
        struct.id,
        struct.typeParameters,
        struct.members.map(::stripLocations),
        struct.requires?.let(::stripLocations),
        struct.annotations
    )
}

fun stripLocations(member: S2Member): S2Member {
    return S2Member(
        member.name,
        stripLocations(member.type)
    )
}

fun stripLocations(union: S2Union): S2Union {
    return S2Union(
        union.id,
        union.typeParameters,
        union.options.map(::stripLocations),
        union.annotations
    )
}

fun stripLocations(option: S2Option): S2Option {
    return S2Option(
        option.name,
        option.type?.let(::stripLocations)
    )
}
