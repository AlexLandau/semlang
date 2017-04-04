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
        val mainFunctionId = FunctionId(Package.EMPTY, "pythagoreanTripleCheck")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        val result123 = interpreter.interpret(mainFunctionId, listOf(toNumType(1), toNumType(2), toNumType(3)))
        assertEquals(SemObject.Boolean(false), result123)
        val result345 = interpreter.interpret(mainFunctionId, listOf(toNumType(3), toNumType(4), toNumType(5)))
        assertEquals(SemObject.Boolean(true), result345)
    }

    @Test
    fun testFunctionRef1() {
        testPythagoreanTripleFunction("src/test/semlang/functionRef1.sem", ::int)
    }

    @Test
    fun testFunctionRef2() {
        testPythagoreanTripleFunction("src/test/semlang/functionRef2.sem", ::int)
    }

    @Test
    fun testFunctionRef3() {
        testPythagoreanTripleFunction("src/test/semlang/functionRef3.sem", ::int)
    }

    @Test
    fun testFunctionRef4() {
        testPythagoreanTripleFunction("src/test/semlang/functionRef3.sem", ::int)
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

    @Test
    fun testStructs4() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs4.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(-1), interpreter.interpret(mainFunctionId, listOf(int(0))))
        assertEquals(int(0), interpreter.interpret(mainFunctionId, listOf(int(1))))
        assertEquals(int(3), interpreter.interpret(mainFunctionId, listOf(int(2))))
        assertEquals(int(8), interpreter.interpret(mainFunctionId, listOf(int(3))))
    }

    @Test
    fun testStructs5() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs5.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(2), interpreter.interpret(mainFunctionId, listOf(natural(0))))
        assertEquals(int(6), interpreter.interpret(mainFunctionId, listOf(natural(1))))
        assertEquals(int(12), interpreter.interpret(mainFunctionId, listOf(natural(2))))
        assertEquals(int(20), interpreter.interpret(mainFunctionId, listOf(natural(3))))
    }

    @Test
    fun testStructs6() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs6.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(1), interpreter.interpret(mainFunctionId, listOf(int(0))))
        assertEquals(int(2), interpreter.interpret(mainFunctionId, listOf(int(1))))
        assertEquals(int(12), interpreter.interpret(mainFunctionId, listOf(int(11))))
    }

    @Test
    fun testStructs7() {
        val functionsMap = parseAndValidateFile("src/test/semlang/structs6.sem")
        val myStuff = Package(listOf("myStuff"))
        val mainFunctionId = FunctionId(myStuff, "myFunction")
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(1), interpreter.interpret(mainFunctionId, listOf(int(0))))
        assertEquals(int(2), interpreter.interpret(mainFunctionId, listOf(int(1))))
        assertEquals(int(12), interpreter.interpret(mainFunctionId, listOf(int(11))))
    }

    @Test
    fun testLists1() {
        val functionsMap = parseAndValidateFile("src/test/semlang/lists1.sem")
        val myStuff = Package(listOf("myCode"))
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(natural(2), interpreter.interpret(FunctionId(myStuff, "listSize2"), listOf()))
        assertEquals(natural(3), interpreter.interpret(FunctionId(myStuff, "listSize3"), listOf()))
    }

    @Test
    fun testLists2() {
        val functionsMap = parseAndValidateFile("src/test/semlang/lists2.sem")
        val myStuff = Package(listOf("myCode"))
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(success(natural(23)), interpreter.interpret(FunctionId(myStuff, "listGet1"), listOf()))
        assertEquals(success(natural(42)), interpreter.interpret(FunctionId(myStuff, "listGet2"), listOf()))
        assertEquals(natural(42), interpreter.interpret(FunctionId(myStuff, "listGetAssume"), listOf()))
        assertEquals(tryFailure(), interpreter.interpret(FunctionId(myStuff, "listGetOutOfBounds"), listOf()))
    }

    @Test
    fun testFunctionBinding1() {
        val functionsMap = parseAndValidateFile("src/test/semlang/functionBinding1.sem")
        val myStuff = Package(listOf("myCode"))
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(2), interpreter.interpret(FunctionId(myStuff, "addThreeV1"), listOf(int(-1))))
        assertEquals(int(5), interpreter.interpret(FunctionId(myStuff, "addThreeV1"), listOf(int(2))))
        assertEquals(int(2), interpreter.interpret(FunctionId(myStuff, "addThreeV2"), listOf(int(-1))))
        assertEquals(int(5), interpreter.interpret(FunctionId(myStuff, "addThreeV2"), listOf(int(2))))
    }

    @Test
    fun testFunctionBinding2() {
        val functionsMap = parseAndValidateFile("src/test/semlang/functionBinding2.sem")
        val myStuff = Package(listOf("myCode"))
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(int(2), interpreter.interpret(FunctionId(myStuff, "addThreeV1"), listOf(int(-1))))
        assertEquals(int(5), interpreter.interpret(FunctionId(myStuff, "addThreeV1"), listOf(int(2))))
        assertEquals(int(2), interpreter.interpret(FunctionId(myStuff, "addThreeV2"), listOf(int(-1))))
        assertEquals(int(5), interpreter.interpret(FunctionId(myStuff, "addThreeV2"), listOf(int(2))))
        assertEquals(natural(3), interpreter.interpret(FunctionId(myStuff, "addThreeV3"), listOf(natural(0))))
        assertEquals(natural(7), interpreter.interpret(FunctionId(myStuff, "addThreeV3"), listOf(natural(4))))
        assertEquals(natural(3), interpreter.interpret(FunctionId(myStuff, "addThreeV4"), listOf(natural(0))))
        assertEquals(natural(7), interpreter.interpret(FunctionId(myStuff, "addThreeV4"), listOf(natural(4))))
    }

    @Test
    fun testSequences1() {
        val functionsMap = parseAndValidateFile("src/test/semlang/sequences1.sem")
        val myStuff = Package(listOf("myStuff"))
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(natural(0), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(0))))
        assertEquals(natural(1), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(1))))
        assertEquals(natural(1), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(2))))
        assertEquals(natural(2), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(3))))
        assertEquals(natural(3), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(4))))
        assertEquals(natural(5), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(5))))
        assertEquals(natural(8), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(6))))
        assertEquals(natural(13), interpreter.interpret(FunctionId(myStuff, "fibonacci"), listOf(natural(7))))
    }

    @Test
    fun testSequences2() {
        val functionsMap = parseAndValidateFile("src/test/semlang/sequences2.sem")
        val myStuff = Package(listOf("myStuff"))
        val interpreter = SemlangForwardInterpreter(functionsMap)
        assertEquals(natural(3), interpreter.interpret(FunctionId(myStuff, "gcd"), listOf(natural(3), natural(6))))
        assertEquals(natural(1), interpreter.interpret(FunctionId(myStuff, "gcd"), listOf(natural(3), natural(7))))
        assertEquals(natural(4), interpreter.interpret(FunctionId(myStuff, "gcd"), listOf(natural(8), natural(12))))
        assertEquals(natural(7), interpreter.interpret(FunctionId(myStuff, "gcd"), listOf(natural(49), natural(21))))
        assertEquals(natural(3), interpreter.interpret(FunctionId(myStuff, "gcd"), listOf(natural(111), natural(54))))
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

private fun success(value: SemObject): SemObject {
    return SemObject.Try.Success(value)
}

private fun tryFailure(): SemObject {
    return SemObject.Try.Failure
}
