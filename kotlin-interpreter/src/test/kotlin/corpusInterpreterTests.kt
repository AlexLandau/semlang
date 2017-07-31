package semlang.interpreter.test

import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.*
import semlang.parser.parseFile
import semlang.parser.validateContext
import semlang.internal.test.runAnnotationTests
import java.io.File

@RunWith(Parameterized::class)
class CorpusInterpreterTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("../semlang-corpus/src/main/semlang")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val validatedContext = parseAndValidateFile(file)
        val testsCount = runAnnotationTests(validatedContext)
        if (testsCount == 0) {
            fail("Expected at least one @Test in file $file, but there were none")
        }
    }
}

private fun parseAndValidateFile(file: File): ValidatedContext {
    val functionsMap2 = parseFile(file)
    return validateContext(functionsMap2, listOf(getNativeContext()))
}
