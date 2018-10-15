package net.semlang.transforms.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.writeToString
import net.semlang.transforms.transformInterfacesToStructs
import net.semlang.validator.ValidationResult
import net.semlang.validator.validateModule
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class InterfacesToStructsTest(private val file: File, private val libraries: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testSimplification() {
        val originalContext = parseFile(file).assumeSuccess()

        val context = transformInterfacesToStructs(originalContext)
        Assert.assertEquals(0, context.interfaces.size)

        val validatedMaybe = try {
            validateModule(context, ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION, libraries)
        } catch(e: NotImplementedError) {
            System.out.println("Transformed context for test $file:")
            System.out.println(writeToString(context))
            throw RuntimeException(e)
        }
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