package net.semlang.test

import net.semlang.modules.getDefaultLocalRepository
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedContext
import net.semlang.api.getNativeContext
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.parseFiles
import net.semlang.parser.validateContext
import java.io.File

private val LIBRARY_MODULE_ID = ModuleId("semlang", "standard-library", "develop-test")

@RunWith(Parameterized::class)
class CorpusInterpreterTests(private val file: File) {
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
            val semlangLibrarySources = File("../semlang-library/src/main/semlang")
            val semlangLibraryFiles = semlangLibrarySources.listFiles().toList()
            val unvalidatedContext = parseFiles(semlangLibraryFiles)

            val standardLibraryContext = validateContext(unvalidatedContext, listOf(getNativeContext()))

            val localRepository = getDefaultLocalRepository()
            localRepository.unpublishIfPresent(LIBRARY_MODULE_ID)
            localRepository.publish(LIBRARY_MODULE_ID, standardLibraryContext)
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

private fun parseAndValidateFile(file: File): ValidatedContext {
    val localRepository = getDefaultLocalRepository()
    val libraryModule = localRepository.loadModule(LIBRARY_MODULE_ID)

    val unvalidatedContext = parseFile(file)
    return validateContext(unvalidatedContext, listOf(getNativeContext(), libraryModule))
}
