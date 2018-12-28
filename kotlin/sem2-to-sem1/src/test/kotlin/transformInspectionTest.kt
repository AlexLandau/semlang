package net.semlang.sem2.translate

import net.semlang.api.Expression
import net.semlang.api.Function
import net.semlang.api.ModuleName
import net.semlang.sem2.api.*
import org.junit.Assert.*
import org.junit.Test

// This tests that some sem2 constructs are translated to the specific sem1 constructs we expect.
class TransformInspectionTest {
    val s2F1Id = EntityId.of("f1")
    val s2IntegerPlus = S2Expression.DotAccess(S2Expression.RawId("Integer"), "plus")
    val s2Integer1 = S2Expression.Literal(S2Type.Integer(), "1")
    @Test
    fun testNamedFunctionCallFromNamespacedFunctionCall() {
        val sem2Function = S2Function(s2F1Id, listOf(), listOf(), S2Type.Integer(), S2Block(
                statements = listOf(),
                returnedExpression = S2Expression.FunctionCall(
                        expression = s2IntegerPlus,
                        arguments = listOf(s2Integer1, s2Integer1),
                        chosenParameters = listOf()
                )
        ), listOf())

        val sem1Function = translateFunction(sem2Function)
        val returnedExpression = sem1Function.block.returnedExpression
        if (returnedExpression !is Expression.NamedFunctionCall) {
            fail("Expected a NamedFunctionCall, but was: $returnedExpression") as Nothing
        }
        assertEquals(net.semlang.api.EntityId.of("Integer", "plus"), returnedExpression.functionRef.id)
    }

}

private fun translateFunction(sem2Function: S2Function): Function {
    val context = S2Context(listOf(sem2Function), listOf(), listOf(), listOf())
    val translated = translateSem2ContextToSem1(context, ModuleName("semlang-test", "test-module"))
    return translated.functions.single()
}
