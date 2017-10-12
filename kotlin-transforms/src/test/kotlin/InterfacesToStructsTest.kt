package net.semlang.transforms

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.simplifyExpressions
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import transformInterfacesToStructs
import java.io.File

@RunWith(Parameterized::class)
class InterfacesToStructsTest(private val file: File) {
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
        val module = validateModule(parseFile(file), ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf())

        val withoutInterfaces = transformInterfacesToStructs(module)

        Assert.assertEquals(0, withoutInterfaces.ownInterfaces.size)
        Assert.assertEquals(0, withoutInterfaces.exportedInterfaces.size)

        try {
            try {
                val testsRun = runAnnotationTests(withoutInterfaces)
                if (testsRun == 0 && file.name.contains("/semlang-corpus/")) {
                    Assert.fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(withoutInterfaces), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(withoutInterfaces), e)
        }

        // TODO: Test sem0 output and parsing round-trip
    }

}