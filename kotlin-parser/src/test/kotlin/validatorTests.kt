package semlang.parser.test

import com.fasterxml.jackson.databind.ObjectMapper
import fromJson
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.ValidatedContext
import semlang.api.getNativeContext
import semlang.parser.parseFile
import semlang.parser.parseString
import semlang.parser.validateContext
import toJson
import writeToString
import java.io.File

@RunWith(Parameterized::class)
class ValidatorPositiveTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val compilerTestFolder = File("src/test/semlang/validatorTests/pass")
            val corpusFolder = File("../semlang-corpus/src/main/semlang")
            val allFiles = compilerTestFolder.listFiles() + corpusFolder.listFiles()
            return allFiles.map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        parseAndValidateFile(file)
    }

    @Test
    fun testParseWriteParseEquality() {
        val initiallyParsed = parseAndValidateFile(file)
        val writtenToString = writeToString(initiallyParsed)
        System.out.println("Rewritten contents for file $file:")
        System.out.println(writtenToString)
        System.out.println("(End contents)")
        val reparsed = parseAndValidateString(writtenToString)
        assertContextsEqual(initiallyParsed, reparsed)
    }

    @Test
    fun testJsonWriteParseEquality() {
        val initiallyParsed = parseAndValidateFile(file)
        val asJson = toJson(initiallyParsed)
        System.out.println("Contents for file $file as JSON:")
        System.out.println(ObjectMapper().writeValueAsString(asJson))
        System.out.println("(End contents)")
        val fromJson = fromJson(asJson)
        val fromJsonValidated = validateContext(fromJson, listOf(getNativeContext()))
        assertContextsEqual(initiallyParsed, fromJsonValidated)
    }
}

fun assertContextsEqual(expected: ValidatedContext, actual: ValidatedContext) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.ownFunctionImplementations, actual.ownFunctionImplementations)
    Assert.assertEquals(expected.ownFunctionSignatures, actual.ownFunctionSignatures)
    Assert.assertEquals(expected.ownStructs, actual.ownStructs)
    Assert.assertEquals(expected.ownInterfaces, actual.ownInterfaces)
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
