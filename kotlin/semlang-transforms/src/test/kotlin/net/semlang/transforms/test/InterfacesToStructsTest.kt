package net.semlang.transforms.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.ValidationResult
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.invalidate
import net.semlang.transforms.transformInterfacesToStructs
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class InterfacesToStructsTest(private val file: File) {
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
        val originalContext = parseFile(file).assumeSuccess()

        val context = transformInterfacesToStructs(originalContext)
        Assert.assertEquals(0, context.interfaces.size)

        val validatedMaybe = validateModule(context, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, listOf())
        if (validatedMaybe is ValidationResult.Failure) {
            System.out.println("Transformed sources for $file:")
            System.out.println(writeToString(context))
            Assert.fail("Transformed output did not pass validation; validation errors: " + validatedMaybe.errors)
        }
        val validated = (validatedMaybe as ValidationResult.Success).module

        Assert.assertEquals(0, validated.ownInterfaces.size)
        Assert.assertEquals(0, validated.exportedInterfaces.size)

        try {
            try {
                val testsRun = runAnnotationTests(validated)
                if (testsRun == 0 && file.name.contains("/semlang-corpus/")) {
                    Assert.fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(validated), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(validated), e)
        }

        // TODO: Test sem0 output and parsing round-trip
    }

}