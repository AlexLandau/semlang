package net.semlang.transforms.test

import net.semlang.api.*
import net.semlang.internal.test.assertModulesEqual
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.parser.parseFile
import net.semlang.transforms.invalidate
import net.semlang.validator.validateModule
import java.io.File

// TODO: Move this to where invalidation is?
@RunWith(Parameterized::class)
class InvalidationTest(private val file: File, private val libraries: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testInvalidateRevalidateRoundTripEquality() {
        val validate = fun(context: RawContext): ValidatedModule {
            return validateModule(context, ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()
        }
        val initialRawContext = parseFile(file).assumeSuccess()
        val initialModule = validate(initialRawContext)

        val invalidated = invalidate(initialModule)
        val revalidated = validate(invalidated)

        assertModulesEqual(initialModule, revalidated)
    }
}
