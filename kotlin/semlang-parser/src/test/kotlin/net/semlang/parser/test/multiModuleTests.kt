package net.semlang.parser.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.validator.validateModule
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

// TODO: Add variants where functions and different constructor types all have name conflicts
@RunWith(Parameterized::class)
class MultiModulePositiveTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val compilerTestFolder = File("src/test/semlang/moduleTests/shouldPass")
            return compilerTestFolder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val module = parseAndValidateModule(file)
        val testCount = runAnnotationTests(module)
        Assert.assertNotEquals("Expected at least one @Test in $file", 0, testCount)
    }
}

@RunWith(Parameterized::class)
class MultiModuleNegativeTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val compilerTestFolder = File("src/test/semlang/moduleTests/shouldNotValidate")
            return compilerTestFolder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        try {
            parseAndValidateModule(file)
            throw AssertionError("File ${file.absolutePath} should have failed validation, but passed")
        } catch(e: Exception) {
            // Expected
        }
    }
}

val TEST_MODULE_1_FILE = File("src/test/semlang/moduleTests/testModule1.sem")
val TEST_MODULE_2_FILE = File("src/test/semlang/moduleTests/testModule2.sem")
val TEST_MODULE_1_ID = ModuleId("semlang-test", "testModule1", "devTest")
val TEST_MODULE_2_ID = ModuleId("semlang-test", "testModule2", "devTest")

private fun parseAndValidateModule(file: File): ValidatedModule {
    val testModule1 = validateModule(parseFile(TEST_MODULE_1_FILE).assumeSuccess(), TEST_MODULE_1_ID, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
    val testModule2 = validateModule(parseFile(TEST_MODULE_2_FILE).assumeSuccess(), TEST_MODULE_2_ID, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
    return validateModule(parseFile(file).assumeSuccess(), ModuleId("semlangTest", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf(testModule1, testModule2)).assumeSuccess()
}
