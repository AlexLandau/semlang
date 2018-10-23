package net.semlang.interpreter.test

import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.api.*
import net.semlang.internal.test.getSemlangNativeCorpusFiles
import net.semlang.internal.test.runAnnotationTests
import net.semlang.validator.parseAndValidateFile
import java.io.File

@RunWith(Parameterized::class)
class CorpusInterpreterTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getSemlangNativeCorpusFiles()
        }
    }

    @Test
    fun test() {
        val validatedModule = parseAndValidateFile(file)
        val testsCount = runAnnotationTests(validatedModule)
        if (testsCount == 0) {
            fail("Expected at least one @Test in file $file, but there were none")
        }
    }
}

private fun parseAndValidateFile(file: File): ValidatedModule {
    return parseAndValidateFile(file, ModuleName("semlang", "corpusFile"), CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()
}
