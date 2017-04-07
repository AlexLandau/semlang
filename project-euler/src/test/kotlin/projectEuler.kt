package semlang.interpreter.test

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import semlang.api.*
import semlang.interpreter.SemObject
import semlang.interpreter.SemlangForwardInterpreter
import semlang.parser.parseFileNamed
import semlang.parser.validateContext
import java.math.BigInteger

class ProjectEulerExamples {
    private fun eulerDot(name: String): FunctionId {
        return FunctionId(Package(listOf("euler")), name)
    }

    @Test
    fun problem1() {
        val interpreter = parseAndValidateFile("src/test/semlang/problem1.sem")
        assertEquals(natural(233168), interpreter.interpret(eulerDot("problem1"), listOf()))
    }

    @Ignore("Blocks on Sequence being made an interface so Sequence.map can work")
    @Test
    fun problem3() {
        val interpreter = parseAndValidateFile("src/test/semlang/problem3.sem")
        assertEquals(natural(42), interpreter.interpret(eulerDot("getLargestPrimeFactor"), listOf(natural(600851475143))))
    }

    private fun parseAndValidateFile(filename: String): SemlangForwardInterpreter {
        val functionsMap2 = parseFileNamed(filename)
        return SemlangForwardInterpreter(validateContext(functionsMap2))
    }
}

private fun int(i: Int): SemObject {
    return SemObject.Integer(BigInteger.valueOf(i.toLong()))
}

private fun natural(l: Long): SemObject {
    return SemObject.Natural(BigInteger.valueOf(l))
}
