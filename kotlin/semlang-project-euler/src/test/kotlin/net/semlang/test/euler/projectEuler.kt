package net.semlang.test.euler

import net.semlang.modules.getDefaultLocalRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import net.semlang.api.*
import net.semlang.internal.test.isRunningInCircle
import net.semlang.interpreter.InterpreterOptions
import net.semlang.interpreter.SemObject
import net.semlang.interpreter.SemlangForwardInterpreter
import net.semlang.parser.parseFileNamed
import net.semlang.parser.validateModule
import org.junit.Assume
import java.math.BigInteger
import java.security.MessageDigest
import java.util.*

class ProjectEulerExamples {
    private fun eulerDot(name: String): EntityId {
        return EntityId.of("euler", name)
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
        // TODO: For now we disable this test in CircleCI because it causes OOMs there for reasons I don't understand.
        Assume.assumeFalse(isRunningInCircle())
        val interpreter = parseAndValidateFile("src/test/semlang/problem3.sem")
        assertEquals(
                "XAnwVUUYpBPljmvFlkupBlVxNIPQsrvJRXKtawtN2ig=",
                hash(interpreter.interpret(eulerDot("getLargestPrimeFactor"), listOf(natural(600851475143)))))
    }

    private fun parseAndValidateFile(filename: String): SemlangForwardInterpreter {
        val rawContext = parseFileNamed(filename).assumeSuccess()

        val standardLibraryContext = getDefaultLocalRepository().loadModule(ModuleId("semlang", "standard-library", "develop"))

        return SemlangForwardInterpreter(validateModule(rawContext, ModuleId("semlang", "eulerTestFile", "develop-test"), CURRENT_NATIVE_MODULE_VERSION, listOf(standardLibraryContext)).assumeSuccess(), InterpreterOptions())
    }
}

// In the spirit of Project Euler, avoid publishing the exact answers...
private fun hash(output: SemObject): String {
    val answer = toLong(output)

    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    val hash: ByteArray = digest.digest(answer.toString().toByteArray(Charsets.UTF_8))

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
