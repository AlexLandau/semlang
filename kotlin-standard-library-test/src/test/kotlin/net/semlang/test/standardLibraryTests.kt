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
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.parseFiles
import net.semlang.parser.validateModule
import java.io.File

private val LIBRARY_MODULE_ID = ModuleId("semlang", "standard-library", "develop-test")

@RunWith(Parameterized::class)
class StandardLibraryTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("../semlang-library-corpus/src/main/semlang")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }


        @BeforeClass
        @JvmStatic
        fun publishStandardLibrary() {
            // TODO: Parse from directory using a standard utility function
            val semlangLibrarySources = File("../semlang-library/src/main/semlang")
            val semlangLibraryFiles = semlangLibrarySources.listFiles{ dir, name -> name.endsWith(".sem") }.toList()
            val unvalidatedContext = parseFiles(semlangLibraryFiles).assumeSuccess()

            val standardLibraryModule = validateModule(unvalidatedContext, LIBRARY_MODULE_ID, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

            val localRepository = getDefaultLocalRepository()
            localRepository.unpublishIfPresent(LIBRARY_MODULE_ID)
            localRepository.publish(standardLibraryModule)
        }
    }


    @Test
    fun test() {
        val validatedContext = parseAndValidateFile(file)
        val testsCount = runAnnotationTests(validatedContext)
        if (testsCount == 0) {
            Assert.fail("Expected at least one @Test in file $file, but there were none")
        }
    }
}

private fun parseAndValidateFile(file: File): ValidatedModule {
    val localRepository = getDefaultLocalRepository()
    val libraryModule = localRepository.loadModule(LIBRARY_MODULE_ID)

    val unvalidatedContext = parseFile(file).assumeSuccess()
    return validateModule(unvalidatedContext, ModuleId("semlang", "testFile", "develop-test"), CURRENT_NATIVE_MODULE_VERSION, listOf(libraryModule)).assumeSuccess()
}
