package semlang.parser.test

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.ValidatedContext
import semlang.api.getNativeContext
import semlang.parser.parseFile
import semlang.parser.parseString
import semlang.parser.validateContext
import writeToString
import java.io.File

@RunWith(Parameterized::class)
class ValidatorPositiveTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("src/test/semlang/validatorTests/pass")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        parseAndValidateFile(file)
    }

    @Test
    @Ignore("Still working on this one... Native struct/interface troubles")
    fun testParseWriteParseEquality() {
        val initiallyParsed = parseAndValidateFile(file)
        val writtenToString = writeToString(initiallyParsed)
        System.out.println("Rewritten contents for file $file:")
        System.out.println(writtenToString)
        System.out.println("(End contents)")
        val reparsed = parseAndValidateString(writtenToString)
        // TODO: Check the actual equality of the contexts
    }
}

@RunWith(Parameterized::class)
class ValidatorNegativeTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("src/test/semlang/validatorTests/fail")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        try {
            val result = parseAndValidateFile(file)
            throw AssertionError("File ${file.absolutePath} should have failed validation, but passed")
        } catch(e: Exception) {
            // Expected
        }
    }
}

private fun parseAndValidateFile(file: File): ValidatedContext {
    val context = parseFile(file)
    return validateContext(context, listOf(getNativeContext()))
}

private fun parseAndValidateString(string: String): ValidatedContext {
    val context = parseString(string)
    return validateContext(context, listOf(getNativeContext()))
}
