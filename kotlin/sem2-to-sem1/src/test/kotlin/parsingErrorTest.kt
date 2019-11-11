package net.semlang.sem2.translate

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.internal.test.ErrorFile
import net.semlang.internal.test.loadErrorFile
import net.semlang.internal.test.writeErrorFileText
import net.semlang.sem2.parser.ParsingResult
import net.semlang.sem2.parser.parseString
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

private val TEST_MODULE_NAME = ModuleName("semlang", "sem2ValidatorTestFile")

@RunWith(Parameterized::class)
class ParsingErrorTest(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return File("../../sem2-parser-tests/failValidator").listFiles().map { file ->
                arrayOf<Any?>(file)
            }
        }
    }

    @Test
    fun test() {
        if (file.readText().contains('\r')) {
            throw AssertionError("File ${file} contains a \\r character; convert to *nix newlines for accurate test results")
        }

        val errorFile = loadErrorFile(file)
        val parsingResult = parseString(errorFile.getText(), file.absolutePath)
        if (parsingResult is ParsingResult.Failure) {
            throw AssertionError("File ${file.absolutePath} should have passed parsing and failed validation, but it failed parsing instead, with errors: ${parsingResult.errors}")
        }
        val translated = translateSem2ContextToSem1(parsingResult.assumeSuccess(), TEST_MODULE_NAME, listOf())
        val result = validate(translated, TEST_MODULE_NAME, CURRENT_NATIVE_MODULE_VERSION, listOf())

        if (result is ValidationResult.Failure) {
            Assert.assertNotEquals(0, result.errors.size)
            if (!errorFile.errors.equals(result.errors.toSet())) {
                val message = "Expected errors:\n" +
                        writeErrorFileText(errorFile) +
                        "\nActual errors:\n" +
                        writeErrorFileText(ErrorFile(errorFile.lines, result.errors.toSet()))
                Assert.fail(message)
            }
        } else {
            throw AssertionError("File ${file.absolutePath} should have failed validation, but passed")
        }
    }
}
