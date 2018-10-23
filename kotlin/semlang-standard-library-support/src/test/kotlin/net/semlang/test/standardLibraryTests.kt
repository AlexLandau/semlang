package net.semlang.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.api.ModuleNonUniqueId
import net.semlang.modules.getDefaultLocalRepository
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getSemlangStandardLibraryCorpusFiles
import net.semlang.internal.test.runAnnotationTests
import net.semlang.interpreter.InterpreterOptions
import net.semlang.parser.parseFile
import net.semlang.validator.validateModule
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
        val libraryModuleId = ModuleNonUniqueId(ModuleName("semlang", "standard-library"), "file", "../../semlang-library/src/main/semlang")
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
        val libraryUniqueId = localRepository.getModuleUniqueId(libraryModuleId, File("."))
        val libraryModule = localRepository.loadModule(libraryUniqueId)

        val unvalidatedContext = parseFile(file).assumeSuccess()
        return validateModule(unvalidatedContext, ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION, listOf(libraryModule)).assumeSuccess()
    }
}
