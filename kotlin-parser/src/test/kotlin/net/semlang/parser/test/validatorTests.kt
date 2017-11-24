package net.semlang.parser.test

import com.fasterxml.jackson.databind.ObjectMapper
import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.parser.*
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
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
        val result = parseAndValidateFile(file)
        Assert.assertTrue(result is ValidationResult.Success)
    }

    @Test
    fun testParseWriteParseEquality() {
        val initiallyParsed = parseAndValidateFile(file).assumeSuccess()
        val writtenToString = writeToString(initiallyParsed)
        System.out.println("Rewritten contents for file $file:")
        System.out.println(writtenToString)
        System.out.println("(End contents)")
        val reparsed = parseAndValidateString(writtenToString)
        assertModulesEqual(initiallyParsed, reparsed)
    }

    @Test
    fun testJsonWriteParseEquality() {
        val initiallyParsed = parseAndValidateFile(file).assumeSuccess()
        val asJson = toJson(initiallyParsed)
        System.out.println("Contents for file $file as JSON:")
        System.out.println(ObjectMapper().writeValueAsString(asJson))
        System.out.println("(End contents)")
        val fromJson = fromJson(asJson)
        val fromJsonValidated = validateModule(fromJson, TEST_MODULE_ID, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        assertModulesEqual(initiallyParsed, fromJsonValidated)
    }
}

fun assertModulesEqual(expected: ValidatedModule, actual: ValidatedModule) {
    // TODO: Check the upstream contexts

    Assert.assertEquals(expected.ownFunctions, actual.ownFunctions)
    Assert.assertEquals(expected.ownStructs, actual.ownStructs)
    Assert.assertEquals(expected.ownInterfaces, actual.ownInterfaces)
    // TODO: Maybe check more?
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
        val result = parseAndValidateFile(file)
        if (result is ValidationResult.Failure) {
            Assert.assertNotEquals(0, result.errors.size)
        } else {
            throw AssertionError("File ${file.absolutePath} should have failed validation, but passed")
        }
    }
}

private val TEST_MODULE_ID = ModuleId("semlang", "validatorTestFile", "devTest")

private fun parseAndValidateFile(file: File): ValidationResult {
    return parseAndValidateFile(file, TEST_MODULE_ID, CURRENT_NATIVE_MODULE_VERSION)
}

private fun parseAndValidateString(string: String): ValidatedModule {
    val context = parseString(string, "testDocumentUri").assumeSuccess()
    return validateModule(context, TEST_MODULE_ID, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
}
