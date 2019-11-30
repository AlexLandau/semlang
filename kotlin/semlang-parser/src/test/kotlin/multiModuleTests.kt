package net.semlang.parser.test

import net.semlang.api.*
import net.semlang.internal.test.ErrorFile
import net.semlang.internal.test.loadErrorFile
import net.semlang.internal.test.runAnnotationTests
import net.semlang.internal.test.writeErrorFileText
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.modules.parseAndValidateModuleDirectory
import net.semlang.parser.parseFile
import net.semlang.parser.parseString
import net.semlang.validator.ValidationResult
import net.semlang.validator.validateModule
import org.junit.Assert
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.net.URI


// TODO: Add variants where functions and different constructor types all have name conflicts
@RunWith(Parameterized::class)
class MultiModulePositiveTests(private val groupFolder: File, private val testFile: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{1}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return File("../../semlang-module-test-cases").listFiles().flatMap { groupFolder ->
                File(groupFolder, "shouldPass").listFiles().orEmpty().map { file ->
                    arrayOf(groupFolder as Any?, file as Any?)
                }
            }
        }
    }

    @Test
    fun test() {
        // TODO: Remove this when type reexporting has been implemented
        Assume.assumeFalse(groupFolder.absolutePath.contains("diamondDependency1"))

        val module = parseAndValidateModule(groupFolder, testFile.readText(), testFile.absolutePath).assumeSuccess()
        val testCount = runAnnotationTests(module)
        Assert.assertNotEquals("Expected at least one @Test in $testFile", 0, testCount)
    }
}

@RunWith(Parameterized::class)
class MultiModuleNegativeTests(private val groupFolder: File, private val testFile: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{1}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return File("../../semlang-module-test-cases").listFiles().flatMap { groupFolder ->
                File(groupFolder, "shouldNotValidate").listFiles().orEmpty().map { file ->
                    arrayOf(groupFolder as Any?, file as Any?)
                }
            }
        }
    }

    @Test
    fun test() {
        // TODO: Remove this when type reexporting has been implemented
        Assume.assumeFalse(groupFolder.absolutePath.contains("diamondDependency1"))

        val errorFile = loadErrorFile(testFile)
        val result = parseAndValidateModule(groupFolder, errorFile.getText(), testFile.absolutePath)
        if (result !is ValidationResult.Failure) {
            throw AssertionError("File ${testFile.absolutePath} should have failed validation, but passed")
        }
        if (errorFile.errors != result.errors.toSet()) {
            for (error in errorFile.errors) {
                println(error.location)
            }
            for (error in result.errors) {
                println(error.location)
            }
            val resultsErrorFile = ErrorFile(errorFile.lines, result.errors.toSet())
            throw AssertionError("Error; expected errors:\n${writeErrorFileText(errorFile)}\nbut got errors:\n${writeErrorFileText(resultsErrorFile)}")
        }
    }
}

private fun parseAndValidateModule(groupFolder: File, testFileText: String, documentUri: String): ValidationResult {
    val allModules = File(groupFolder, "modules").listFiles().map { moduleDir ->
        parseAndValidateModuleDirectory(moduleDir, CURRENT_NATIVE_MODULE_VERSION, getDefaultLocalRepository()).assumeSuccess()
    }

    return validateModule(parseString(testFileText, documentUri).assumeSuccess(), ModuleName("semlangTest", "testFile"), CURRENT_NATIVE_MODULE_VERSION, allModules)
}
