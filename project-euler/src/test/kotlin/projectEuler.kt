package semlang.interpreter.test

import org.junit.Assert.assertEquals
import org.junit.Test
import semlang.api.FunctionId
import semlang.api.Package
import semlang.api.ValidatedContext
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

    private fun parseAndValidateFile(filename: String): SemlangForwardInterpreter {
        val functionsMap2 = parseFileNamed(filename)
        return SemlangForwardInterpreter(validateContext(functionsMap2).assume())
    }
}

private fun int(i: Int): SemObject {
    return SemObject.Integer(BigInteger.valueOf(i.toLong()))
}

private fun natural(i: Int): SemObject {
    return SemObject.Natural(BigInteger.valueOf(i.toLong()))
}
