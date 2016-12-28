package semlang.parser.test

import org.junit.Assert.assertEquals
import org.junit.Test
import semlang.api.Function
import semlang.api.FunctionId
import semlang.api.Package
import semlang.interpreter.SemObject
import semlang.interpreter.SemlangForwardInterpreter
import semlang.parser.tokenize
import java.math.BigInteger

class JUnitTests {
    @Test
    fun runPythagoreanTripleFunction1() {
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple1.sem")
    }

    @Test
    fun runPythagoreanTripleFunction2() {
        // Tests the use of an additional function
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple2.sem")
    }

    private fun testPythagoreanTripleFunction(filename: String) {
        val functions = tokenize(filename)
        val functionsMap = mapById(functions)
        val mainFunctionId = FunctionId(Package(listOf()), "pythagoreanTripleCheck")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        val result123 = interpreter.interpret(mainFunctionId, listOf(int(1), int(2), int(3)))
        assertEquals(SemObject.Boolean(false), result123)
        val result345 = interpreter.interpret(mainFunctionId, listOf(int(3), int(4), int(5)))
        assertEquals(SemObject.Boolean(true), result345)
    }

    private fun mapById(functions: List<Function>): Map<FunctionId, Function> {
        return functions.associateBy(Function::id)
    }

    private fun int(i: Int): SemObject {
        return SemObject.Integer(BigInteger.valueOf(i.toLong()))
    }
}
