package net.semlang.transforms

import net.semlang.api.ModuleId
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import java.io.File

@RunWith(Parameterized::class)
class SimplifyExpressionsTest(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val compilerTestsFolder = File("../kotlin-parser/src/test/semlang/validatorTests/pass")
            val corpusFolder = File("../semlang-corpus/src/main/semlang")
            val allFiles = compilerTestsFolder.listFiles() + corpusFolder.listFiles()
            return allFiles.map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun testSimplification() {
        val module = validateModule(parseFile(file), ModuleId("semlang", "testFile", "devTest"), listOf())

        // TODO: Check that the semantics of any examples are actually unchanged, according to our interpreter
        // (This requires storing inputs to try with each given code sample; ideally we'd combine this
        // with storing the expected outputs, so interpreter tests can use the same framework)
        val simplified = simplifyExpressions(module)

        try {
            try {
                val testsRun = runAnnotationTests(simplified)
                if (testsRun == 0 && file.name.contains("/semlang-corpus/")) {
                    fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(simplified), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(simplified), e)
        }

        // TODO: Test sem0 output and parsing round-trip
    }

}
