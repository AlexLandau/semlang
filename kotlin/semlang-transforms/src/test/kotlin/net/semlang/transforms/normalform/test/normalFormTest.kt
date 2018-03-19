package net.semlang.transforms.normalform.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.parseFile
import net.semlang.parser.validateModule
import net.semlang.parser.writeToString
import net.semlang.transforms.normalform.getNormalFormContents
import net.semlang.transforms.normalform.replaceFunctionBlock
import net.semlang.transforms.simplifyAllExpressions
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

// TODO: Issue that needs to be resolved before future work: We want the ordering of expressions to be consistent when
// the function is identical, but the assumption was that a simple rule like "opening function call is always f(v1, v2, v3, ...)"
// would take care of it. But that ends up being inconsistent with the toposorted and deduplicated assumptions, i.e. v3
// (as defined in this way) could depend on v2.
//
// Is there some clever way to solve this? Would it perhaps be sufficient to first change things into this order (or use
// it to add the elements in a deterministic order) and then use a deterministic toposorting algorithm?

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

        val modifiedFunctions = originalContext.functions.map { function ->
            val seminormalForm = getNormalFormContents(function)
            replaceFunctionBlock(function, seminormalForm)
        }
        val modifiedContext = originalContext.copy(functions = modifiedFunctions)

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
