package net.semlang.transforms.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.simplifyExpressions
import java.io.File

@RunWith(Parameterized::class)
class SimplifyExpressionsTest(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val compilerTestsFolder = File("../semlang-parser/src/test/semlang/validatorTests/pass")
            val corpusFolder = File("../../semlang-corpus/src/main/semlang")
            val allFiles = compilerTestsFolder.listFiles() + corpusFolder.listFiles()
            return allFiles.map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun testSimplification() {
        val module = validateModule(parseFile(file).assumeSuccess(), ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

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
