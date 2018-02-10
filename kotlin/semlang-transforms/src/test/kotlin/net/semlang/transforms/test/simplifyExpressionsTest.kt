package net.semlang.transforms.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.internal.test.getAllStandaloneCompilableFiles
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
            return getAllStandaloneCompilableFiles()
        }
    }

    @Test
    fun testSimplification() {
        val originalContext = parseFile(file).assumeSuccess()
        val simplifiedContext = simplifyExpressions(originalContext)
        val simplifiedModule = validateModule(simplifiedContext, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

        try {
            try {
                val testsRun = runAnnotationTests(simplifiedModule)
                if (testsRun == 0 && file.name.contains("/semlang-corpus/")) {
                    fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(simplifiedContext), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(simplifiedContext), e)
        }

        // TODO: Test sem0 output and parsing round-trip
    }

}
