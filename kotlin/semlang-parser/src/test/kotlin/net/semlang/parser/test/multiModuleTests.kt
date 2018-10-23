package net.semlang.parser.test

import net.semlang.api.*
import net.semlang.internal.test.runAnnotationTests
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.modules.parseAndValidateModuleDirectory
import net.semlang.parser.parseFile
import net.semlang.validator.validateModule
import org.junit.Assert
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File


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
        val module = parseAndValidateModule(groupFolder, testFile)
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
        try {
            parseAndValidateModule(groupFolder, testFile)
            throw AssertionError("File ${testFile.absolutePath} should have failed validation, but passed")
        } catch(e: Exception) {
            // Expected
        }
    }
}

private fun parseAndValidateModule(groupFolder: File, testFile: File): ValidatedModule {
    val allModules = File(groupFolder, "modules").listFiles().map { moduleDir ->
        parseAndValidateModuleDirectory(moduleDir, CURRENT_NATIVE_MODULE_VERSION, getDefaultLocalRepository()).assumeSuccess()
    }

    return validateModule(parseFile(testFile).assumeSuccess(), ModuleName("semlangTest", "testFile"), CURRENT_NATIVE_MODULE_VERSION, allModules).assumeSuccess()
}
