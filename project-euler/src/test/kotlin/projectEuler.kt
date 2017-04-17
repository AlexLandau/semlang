package semlang.interpreter.test

import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import semlang.api.*
import semlang.interpreter.SemObject
import semlang.interpreter.SemlangForwardInterpreter
import semlang.parser.parseFileAgainstStandardLibrary
import semlang.parser.parseFileNamed
import semlang.parser.validateContext
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*

class ProjectEulerExamples {
    private fun eulerDot(name: String): FunctionId {
        return FunctionId(Package(listOf("euler")), name)
    }

    @Test
    fun problem1() {
        val interpreter = parseAndValidateFile("src/test/semlang/problem1.sem")
        assertEquals(
                "wLIPRmXQOI1WTwtuzz7cn5SAyxX/+HGYuVcB2fX+H3s=",
                hash(interpreter.interpret(eulerDot("problem1"), listOf())))
    }

    // TODO: This is slow currently (around 7 seconds)
    @Test
    fun problem3() {
        val interpreter = parseAndValidateFile("src/test/semlang/problem3.sem")
        assertEquals(
                "XAnwVUUYpBPljmvFlkupBlVxNIPQsrvJRXKtawtN2ig=",
                hash(interpreter.interpret(eulerDot("getLargestPrimeFactor"), listOf(natural(600851475143)))))
    }

    private fun parseAndValidateFile(filename: String): SemlangForwardInterpreter {
        val functionsMap2 = parseFileAgainstStandardLibrary(filename)
        return SemlangForwardInterpreter(validateContext(functionsMap2, listOf(getNativeContext())))
    }
}

// In the spirit of Project Euler, avoid publishing the exact answers...
private fun hash(output: SemObject): String {
    val answer = toLong(output)

    val digest: MessageDigest = MessageDigest.getInstance("SHA-256");
    val hash: ByteArray = digest.digest(answer.toString().toByteArray(Charsets.UTF_8));

    return Base64.getEncoder().encodeToString(hash)
}

fun toLong(output: SemObject): Long {
    when (output) {
        is SemObject.Natural -> return output.value.longValueExact()
        is SemObject.Integer -> return output.value.longValueExact()
    }
    error("Unexpected output type")
}

private fun int(i: Int): SemObject {
    return SemObject.Integer(BigInteger.valueOf(i.toLong()))
}

private fun natural(l: Long): SemObject {
    return SemObject.Natural(BigInteger.valueOf(l))
}
