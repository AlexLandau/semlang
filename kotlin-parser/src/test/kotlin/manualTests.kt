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
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple1.sem", ::int)
    }

    @Test
    fun runPythagoreanTripleFunction2() {
        // Tests the use of an additional function
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple2.sem", ::int)
    }

    @Test
    fun runPythagoreanTripleFunction3() {
        // Tests the use of the natural number type
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple3.sem", ::natural)
    }

    @Test
    fun runPythagoreanTripleFunction4() {
        // Tests the use of boolean literals
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple4.sem", ::int)
    }

    private fun testPythagoreanTripleFunction(filename: String, toNumType: (Int) -> SemObject) {
        val functions = tokenize(filename)
        val functionsMap = mapById(functions)
        val mainFunctionId = FunctionId(Package(listOf()), "pythagoreanTripleCheck")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        val result123 = interpreter.interpret(mainFunctionId, listOf(toNumType(1), toNumType(2), toNumType(3)))
        assertEquals(SemObject.Boolean(false), result123)
        val result345 = interpreter.interpret(mainFunctionId, listOf(toNumType(3), toNumType(4), toNumType(5)))
        assertEquals(SemObject.Boolean(true), result345)
    }
}

private fun mapById(functions: List<Function>): Map<FunctionId, Function> {
    return functions.associateBy(Function::id)
}

private fun int(i: Int): SemObject {
    return SemObject.Integer(BigInteger.valueOf(i.toLong()))
}

private fun natural(i: Int): SemObject {
    return SemObject.Natural(BigInteger.valueOf(i.toLong()))
}
