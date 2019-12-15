package net.semlang.sem2.translate

import net.semlang.api.Expression
import net.semlang.api.Function
import net.semlang.api.ModuleName
import net.semlang.api.Statement
import net.semlang.sem2.api.*
import org.junit.Assert.*
import org.junit.Test

// This tests that some sem2 constructs are translated to the specific sem1 constructs we expect.
class TransformInspectionTest {
    val s2F1Id = EntityId.of("f1")
    val s2IntegerPlus = S2Expression.DotAccess(S2Expression.RawId("Integer"), "plus")
    val s2IntType = S2Type.NamedType(EntityRef(null, EntityId.of("Integer")), false)
    val s2Integer1 = S2Expression.Literal(s2IntType, "1")

    @Test
    fun testNamedFunctionCallFromNamespacedFunctionCall() {
        val sem2Function = S2Function(s2F1Id, listOf(), listOf(), s2IntType, S2Block(
                statements = listOf(),
                lastStatement = S2Statement.Bare(S2Expression.FunctionCall( // Integer.plus(Integer."1", Integer."1")
                        expression = s2IntegerPlus,
                        arguments = listOf(s2Integer1, s2Integer1),
                        chosenParameters = listOf()
                ))
        ), listOf())

        val sem1Function = translateFunction(sem2Function)
        val lastStatement = sem1Function.block.lastStatement
        if (lastStatement !is Statement.Bare) {
            fail("Expected a bare statement, but was: $lastStatement") as Nothing
        }
        val returnedExpression = lastStatement.expression
        if (returnedExpression !is Expression.NamedFunctionCall) {
            fail("Expected a NamedFunctionCall, but was: $returnedExpression") as Nothing
        }
        assertEquals(net.semlang.api.EntityId.of("Integer", "plus"), returnedExpression.functionRef.id)
    }

    @Test
    fun testNamedFunctionCallFromLocalFunctionCall() {
        val sem2Function = S2Function(s2F1Id, listOf(), listOf(), s2IntType, S2Block(
                statements = listOf(),
                lastStatement = S2Statement.Bare(S2Expression.FunctionCall( // Integer."1".plus(Integer."1")
                        expression = S2Expression.DotAccess( // Integer."1".plus
                                subexpression = s2Integer1,
                                name = "plus"
                        ),
                        arguments = listOf(s2Integer1),
                        chosenParameters = listOf()
                ))
        ), listOf())

        val sem1Function = translateFunction(sem2Function)
        val lastStatement = sem1Function.block.lastStatement
        if (lastStatement !is Statement.Bare) {
            fail("Expected a bare statement, but was: $lastStatement") as Nothing
        }
        val returnedExpression = lastStatement.expression
        if (returnedExpression !is Expression.NamedFunctionCall) {
            fail("Expected a NamedFunctionCall, but was: $returnedExpression") as Nothing
        }
        assertEquals(net.semlang.api.EntityId.of("Integer", "plus"), returnedExpression.functionRef.id)
    }
}

private fun translateFunction(sem2Function: S2Function): Function {
    val context = S2Context(listOf(sem2Function), listOf(), listOf())
    val translated = translateSem2ContextToSem1(context, ModuleName("semlang-test", "test-module"), listOf(),
            Sem2ToSem1Options(failOnUninferredType = true))
    return translated.assumeSuccess().functions.single()
}
