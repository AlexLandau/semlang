package net.semlang.linker

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.runAnnotationTests
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.parser.parseAndValidateModuleDirectory
import net.semlang.parser.parseFile
import net.semlang.parser.validate
import net.semlang.parser.validateModule
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File


@RunWith(Parameterized::class)
class StandardLibraryTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("../../semlang-library-corpus/src/main/semlang")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }

        var libraryModuleId: ModuleId? = null

        @BeforeClass
        @JvmStatic
        fun publishStandardLibrary() {
            val standardLibraryFolder = File("../../semlang-library/src/main/semlang")
            val standardLibraryModule = parseAndValidateModuleDirectory(standardLibraryFolder, CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()
            this.libraryModuleId = standardLibraryModule.id

            val localRepository = getDefaultLocalRepository()
            localRepository.unpublishIfPresent(standardLibraryModule.id)
            localRepository.publish(standardLibraryModule)
        }
    }


    @Test
    fun test() {
        val unlinkedModule = parseAndValidateFile(file)
        val linkedContext = linkModuleWithDependencies(unlinkedModule)
        val validatedModule = validateModule(linkedContext.contents, linkedContext.info.id, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

        // Check that the names of exported things are not changed
        Assert.assertEquals(unlinkedModule.exportedFunctions, validatedModule.exportedFunctions)
        Assert.assertEquals(unlinkedModule.exportedStructs, validatedModule.exportedStructs)
        Assert.assertEquals(unlinkedModule.exportedInterfaces, validatedModule.exportedInterfaces)

        val testsCount = runAnnotationTests(validatedModule)
        if (testsCount == 0) {
            Assert.fail("Expected at least one @Test in file $file, but there were none")
        }
    }

    private fun parseAndValidateFile(file: File): ValidatedModule {
        val localRepository = getDefaultLocalRepository()
        val libraryModule = localRepository.loadModule(libraryModuleId!!)

        val unvalidatedContext = parseFile(file).assumeSuccess()
        return validateModule(unvalidatedContext, ModuleId("semlang", "testFile", "develop-test"), CURRENT_NATIVE_MODULE_VERSION, listOf(libraryModule)).assumeSuccess()
    }
}
