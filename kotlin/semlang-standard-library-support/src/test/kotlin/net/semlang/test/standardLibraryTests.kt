package net.semlang.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.modules.getDefaultLocalRepository
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getSemlangStandardLibraryCorpusFiles
import net.semlang.internal.test.runAnnotationTests
import net.semlang.interpreter.InterpreterOptions
import net.semlang.parser.parseAndValidateModuleDirectory
import net.semlang.parser.parseFile
import net.semlang.parser.parseFiles
import net.semlang.parser.validateModule
import java.io.File

@RunWith(Parameterized::class)
class StandardLibraryTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getSemlangStandardLibraryCorpusFiles()
        }

        // TODO: Can we get this from the semlang-standard-library-support project? Or should it be "too standard to fail"?
        val libraryModuleId: ModuleId = ModuleId("semlang", "standard-library", "develop")
    }


    @Test
    fun testWithOptimizations() {
        val validatedContext = parseAndValidateFile(file)
        val testsCount = runAnnotationTests(validatedContext, InterpreterOptions(useLibraryOptimizations = true))
        if (testsCount == 0) {
            Assert.fail("Expected at least one @Test in file $file, but there were none")
        }
    }

    @Test
    fun testWithoutOptimizations() {
        val validatedContext = parseAndValidateFile(file)
        val testsCount = runAnnotationTests(validatedContext, InterpreterOptions(useLibraryOptimizations = false))
        if (testsCount == 0) {
            Assert.fail("Expected at least one @Test in file $file, but there were none")
        }
    }

    private fun parseAndValidateFile(file: File): ValidatedModule {
        val localRepository = getDefaultLocalRepository()
        val libraryModule = localRepository.loadModule(libraryModuleId)

        val unvalidatedContext = parseFile(file).assumeSuccess()
        return validateModule(unvalidatedContext, ModuleId("semlang", "testFile", "develop-test"), CURRENT_NATIVE_MODULE_VERSION, listOf(libraryModule)).assumeSuccess()
    }
}
