package semlang.interpreter.test

import org.junit.Assert.assertEquals
import org.junit.Test
import semlang.api.Function
import semlang.api.FunctionId
import semlang.api.Package
import semlang.api.ValidatedContext
import semlang.interpreter.SemObject
import semlang.interpreter.SemlangForwardInterpreter
import semlang.parser.parseFileNamed
import semlang.parser.validateContext
import java.math.BigInteger

class InterpreterTests {
    @Test
    fun testPythagoreanTripleFunction1() {
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple1.sem", ::int)
    }

    @Test
    fun testPythagoreanTripleFunction2() {
        // Tests the use of an additional function
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple2.sem", ::int)
    }

    @Test
    fun testPythagoreanTripleFunction3() {
        // Tests the use of the natural number type
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple3.sem", ::natural)
    }

    @Test
    fun testPythagoreanTripleFunction4() {
        // Tests the use of boolean literals
        testPythagoreanTripleFunction("src/test/semlang/pythagoreanTriple4.sem", ::int)
    }

    private fun testPythagoreanTripleFunction(filename: String, toNumType: (Int) -> SemObject) {
        val functionsMap = parseAndValidateFile(filename)
        val mainFunctionId = FunctionId(Package(listOf()), "pythagoreanTripleCheck")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        val result123 = interpreter.interpret(mainFunctionId, listOf(toNumType(1), toNumType(2), toNumType(3)))
        assertEquals(SemObject.Boolean(false), result123)
        val result345 = interpreter.interpret(mainFunctionId, listOf(toNumType(3), toNumType(4), toNumType(5)))
        assertEquals(SemObject.Boolean(true), result345)
    }

    @Test
    fun testLiterals1() {
        val functions = parseAndValidateFile("src/test/semlang/literals1.sem")
        val fnId = functions.functions.keys.single()
        val interpreter = SemlangForwardInterpreter(functions)
        assertEquals(int(2), interpreter.interpret(fnId, listOf(int(1))))
        assertEquals(int(5), interpreter.interpret(fnId, listOf(int(2))))
        assertEquals(int(1), interpreter.interpret(fnId, listOf(int(0))))
        assertEquals(int(2), interpreter.interpret(fnId, listOf(int(-1))))
    }

    @Test
    fun testLiterals2() {
        val functions = parseAndValidateFile("src/test/semlang/literals2.sem")
        val fnId = functions.functions.keys.single()
        val interpreter = SemlangForwardInterpreter(functions)
        assertEquals(natural(2), interpreter.interpret(fnId, listOf(natural(1))))
        assertEquals(natural(5), interpreter.interpret(fnId, listOf(natural(2))))
        assertEquals(natural(1), interpreter.interpret(fnId, listOf(natural(0))))
    }

    @Test
    fun testStructs1() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs1.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(-1), interpreter.interpret(mainFunctionId, listOf(int(0))))
        assertEquals(int(0), interpreter.interpret(mainFunctionId, listOf(int(1))))
        assertEquals(int(3), interpreter.interpret(mainFunctionId, listOf(int(2))))
        assertEquals(int(8), interpreter.interpret(mainFunctionId, listOf(int(3))))
    }

    @Test
    fun testStructs2() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs2.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(-1), interpreter.interpret(mainFunctionId, listOf(int(0))))
        assertEquals(int(0), interpreter.interpret(mainFunctionId, listOf(int(1))))
        assertEquals(int(3), interpreter.interpret(mainFunctionId, listOf(int(2))))
        assertEquals(int(8), interpreter.interpret(mainFunctionId, listOf(int(3))))
    }

    @Test
    fun testStructs3() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs3.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(-1), interpreter.interpret(mainFunctionId, listOf(int(0))))
        assertEquals(int(0), interpreter.interpret(mainFunctionId, listOf(int(1))))
        assertEquals(int(3), interpreter.interpret(mainFunctionId, listOf(int(2))))
        assertEquals(int(8), interpreter.interpret(mainFunctionId, listOf(int(3))))
    }

    private fun parseAndValidateFile(filename: String): ValidatedContext {
        val functionsMap2 = parseFileNamed(filename)
        return validateContext(functionsMap2).assume()
    }
}

private fun int(i: Int): SemObject {
    return SemObject.Integer(BigInteger.valueOf(i.toLong()))
}

private fun natural(i: Int): SemObject {
    return SemObject.Natural(BigInteger.valueOf(i.toLong()))
}
