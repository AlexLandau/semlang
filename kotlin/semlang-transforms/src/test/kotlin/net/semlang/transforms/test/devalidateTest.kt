package net.semlang.transforms.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.RawContext
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.assertModulesEqual
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.devalidate
import net.semlang.transforms.simplifyExpressions
import java.io.File

@RunWith(Parameterized::class)
class DevalidationTest(private val file: File) {
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
    fun testDevalidateRevalidateRoundTripEquality() {
        val validate = fun(context: RawContext): ValidatedModule {
            return validateModule(context, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        }
        val initialRawContext = parseFile(file).assumeSuccess()
        val initialModule = validate(initialRawContext)//validateModule(initialRawContext, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

        val devalidated = devalidate(initialModule)
        val revalidated = validate(devalidated)

        assertModulesEqual(initialModule, revalidated)
    }
}
