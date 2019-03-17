package net.semlang.linker

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.api.ModuleNonUniqueId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getAllStandaloneCompilableFiles
import net.semlang.internal.test.getSemlangStandardLibraryCorpusFiles
import net.semlang.internal.test.runAnnotationTests
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.parser.parseFile
import net.semlang.parser.writeToString
import net.semlang.validator.parseAndValidateFile
import net.semlang.validator.validateModule
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File


@RunWith(Parameterized::class)
class StandaloneFileLinkingTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getAllStandaloneCompilableFiles()
        }
    }

    @Test
    fun test() {
        val unlinkedModule = parseAndValidateFile(file, ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()
        val linkedContext = linkModuleWithDependencies(unlinkedModule)
        val validatedModule = validateModule(linkedContext.contents, linkedContext.info.name, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

        // Check that the names of exported things are not changed
        Assert.assertEquals(unlinkedModule.exportedFunctions, validatedModule.exportedFunctions)
        Assert.assertEquals(unlinkedModule.exportedStructs, validatedModule.exportedStructs)
        Assert.assertEquals(unlinkedModule.exportedUnions, validatedModule.exportedUnions)

        val testsCount = runAnnotationTests(validatedModule)
        // TODO: Improve how this check works
        if (file.absolutePath.contains("corpus")) {
            if (testsCount == 0) {
                Assert.fail("Expected at least one @Test in file $file, but there were none")
            }
        }
    }
}

@RunWith(Parameterized::class)
class StandardLibraryTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getSemlangStandardLibraryCorpusFiles()
        }
    }

    @Test
    fun test() {
        val unlinkedModule = parseAndValidateFile(file)
        val linkedContext = linkModuleWithDependencies(unlinkedModule)
        val validatedModule = try {
            validateModule(linkedContext.contents, linkedContext.info.name, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        } catch (t: Throwable) {
            throw AssertionError("Contents of linked context are: ${writeToString(linkedContext.contents)}", t)
        }

        // Check that the names of exported things are not changed
        Assert.assertEquals(unlinkedModule.exportedFunctions, validatedModule.exportedFunctions)
        Assert.assertEquals(unlinkedModule.exportedStructs, validatedModule.exportedStructs)
        Assert.assertEquals(unlinkedModule.exportedUnions, validatedModule.exportedUnions)

        val testsCount = runAnnotationTests(validatedModule)
        if (testsCount == 0) {
            Assert.fail("Expected at least one @Test in file $file, but there were none")
        }
    }

    private fun parseAndValidateFile(file: File): ValidatedModule {
        val standardLibraryModuleId = ModuleNonUniqueId(ModuleName("semlang", "standard-library"), "file", "../../semlang-library/src/main/semlang")
        val standardLibraryUniqueId = getDefaultLocalRepository().getModuleUniqueId(standardLibraryModuleId, File("."))
        val standardLibraryModule = getDefaultLocalRepository().loadModule(standardLibraryUniqueId)

        val unvalidatedContext = parseFile(file).assumeSuccess()
        return validateModule(unvalidatedContext, ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION, listOf(standardLibraryModule)).assumeSuccess()
    }
}
