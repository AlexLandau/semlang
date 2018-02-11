package net.semlang.transforms.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getAllStandaloneCompilableFiles
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.convertFromSem0
import net.semlang.transforms.convertToSem0
import net.semlang.transforms.simplifyExpressions
import java.io.File

@RunWith(Parameterized::class)
class ToSem0Test(private val file: File, private val libraries: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testSem0Conversion() {
        val module = validateModule(parseFile(file).assumeSuccess(), ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()
        val converted = convertToSem0(module)

        // TODO: Do something with the converted module
        val afterRoundTrip = convertFromSem0(converted)
        // TODO: If/when sem0 includes linking in libraries, we shouldn't pass in libraries here
        val revalidated = validateModule(afterRoundTrip, ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()

        try {
            try {
                val testsRun = runAnnotationTests(revalidated)
                if (testsRun == 0 && file.name.contains("/semlang-corpus/")) {
                    fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(revalidated), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(revalidated), e)
        }

        // TODO: Test sem0 output and parsing round-trip
    }

}
