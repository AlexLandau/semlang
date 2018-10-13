package net.semlang.parser.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.assertModulesEqual
import net.semlang.internal.test.assertRawContextsEqual
import net.semlang.internal.test.getAllStandaloneCompilableFiles
import net.semlang.parser.*
import net.semlang.validator.ValidationResult
import net.semlang.validator.parseAndValidateFile
import net.semlang.validator.validate
import net.semlang.validator.validateModule
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
            return getAllStandaloneCompilableFiles()
        }
    }

    @Test
    fun test() {
        parseAndValidateFile(file).assumeSuccess()
    }

    @Test
    fun testValidateWriteValidateEquality() {
        val initiallyParsed = parseAndValidateFile(file).assumeSuccess()
        val writtenToString = writeToString(initiallyParsed)
//        System.out.println("Rewritten contents for file $file:")
//        System.out.println(writtenToString)
//        System.out.println("(End contents)")
        try {
            val reparsed = parseAndValidateString(writtenToString)
            assertModulesEqual(initiallyParsed, reparsed)
        } catch (t: Throwable) {
            throw AssertionError("Error; written version was: $writtenToString", t)
        }
    }

    @Test
    fun testParseWriteParseEquality() {
        val initiallyParsed = parseFile(file).assumeSuccess()
        val writtenToString = writeToString(initiallyParsed)
//        System.out.println("Rewritten contents for file $file:")
//        System.out.println(writtenToString)
//        System.out.println("(End contents)")
        try {
            val reparsed = parseString(writtenToString, "").assumeSuccess()
            assertRawContextsEqual(initiallyParsed, reparsed)
        } catch (t: Throwable) {
            throw AssertionError("Error while reparsing; written version was: $writtenToString", t)
        }
    }

    @Test
    fun testJsonWriteParseEquality() {
        val initiallyParsed = parseAndValidateFile(file).assumeSuccess()
        val asJson = toJson(initiallyParsed)
//        System.out.println("Contents for file $file as JSON:")
//        System.out.println(ObjectMapper().writeValueAsString(asJson))
//        System.out.println("(End contents)")
        val fromJson = fromJson(asJson)
        val fromJsonValidated = validateModule(fromJson, TEST_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        assertModulesEqual(initiallyParsed, fromJsonValidated)
    }
}

@RunWith(Parameterized::class)
class ParserNegativeTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("src/test/semlang/validatorTests/failParser")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val result = parseFile(file)
        if (result is ParsingResult.Failure) {
            Assert.assertNotEquals(0, result.errors.size)
        } else {
            throw AssertionError("File ${file.absolutePath} should have failed parsing, but passed")
        }
    }
}

@RunWith(Parameterized::class)
class ValidatorNegativeTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("src/test/semlang/validatorTests/failValidator")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val parsingResult = parseFile(file)
        if (parsingResult is ParsingResult.Failure) {
            throw AssertionError("File ${file.absolutePath} should have passed parsing and failed validation, but it failed parsing instead, with errors: ${parsingResult.errors}")
        }
        val result = validate(parsingResult, TEST_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION, listOf())
        if (result is ValidationResult.Failure) {
            Assert.assertNotEquals(0, result.errors.size)
        } else {
            throw AssertionError("File ${file.absolutePath} should have failed validation, but passed")
        }
    }
}

private val TEST_MODULE_NAME = ModuleName("semlang", "validatorTestFile")

private fun parseAndValidateFile(file: File): ValidationResult {
    return parseAndValidateFile(file, TEST_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION)
}

private fun parseAndValidateString(string: String): ValidatedModule {
    val context = parseString(string, "testDocumentUri").assumeSuccess()
    return validateModule(context, TEST_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
}
