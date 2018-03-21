package net.semlang.transforms.normalform.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.normalform.convertFunctionsToNormalForm
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class NormalFormTest(private val file: File, private val libraries: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testNormalFormConversion() {
        val originalContext = parseFile(file).assumeSuccess()
        val originalModule = validateModule(originalContext, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()

        val modifiedContext = convertFunctionsToNormalForm(originalModule)
        val modifiedModule = try {
            validateModule(modifiedContext, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()
        } catch(e: RuntimeException) {
            throw RuntimeException("Modified context was:\n${writeToString(modifiedContext)}", e)
        }

        try {
            try {
                val testsRun = runAnnotationTests(modifiedModule)
                if (testsRun == 0 && file.name.contains("/semlang-corpus/")) {
                    Assert.fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Modified context was:\n" + writeToString(modifiedModule), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Modified context was:\n" + writeToString(modifiedModule), e)
        }
    }

}
