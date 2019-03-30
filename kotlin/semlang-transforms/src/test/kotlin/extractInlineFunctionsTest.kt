package net.semlang.transforms.test

import net.semlang.api.*
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import net.semlang.internal.test.runAnnotationTests
import net.semlang.linker.linkModuleWithDependencies
import net.semlang.parser.parseFile
import net.semlang.parser.writeToString
import net.semlang.transforms.extractInlineFunctions
import net.semlang.transforms.replaceSomeExpressionsPostvisit
import net.semlang.validator.validateModule
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class ExtractInlineFunctionsTest(private val file: File, private val libraries: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testExtraction() {
        testExtraction(false)
    }

    @Test
    fun testLinkedExtraction() {
        testExtraction(true)
    }

    private fun testExtraction(linked: Boolean) {
        var module = validateModule(parseFile(file).assumeSuccess(), ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()
        if (linked) {
            val linkedModule = linkModuleWithDependencies(module)
            module = validateModule(linkedModule.contents, linkedModule.info.name, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        }

        val withoutInlineFunctions = extractInlineFunctions(module)
        val validated = try {
            validateModule(withoutInlineFunctions, ModuleName("semlang", "testFile"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()
        } catch (e: RuntimeException) {
            throw RuntimeException("Validation after extraction failed; context was:\n" + writeToString(withoutInlineFunctions), e)
        }

        fun verifyNoInlineFunctionsRemain(block: Block) {
            replaceSomeExpressionsPostvisit(block, { expression: Expression ->
                if (expression is Expression.InlineFunction) {
                    Assert.fail("An inline function was not removed: $expression")
                }
                null
            })
        }
        for (function in withoutInlineFunctions.functions) {
            verifyNoInlineFunctionsRemain(function.block)
        }
        for (struct in withoutInlineFunctions.structs) {
            val requires = struct.requires
            if (requires != null) {
                verifyNoInlineFunctionsRemain(requires)
            }
        }

        try {
            try {
                val testsRun = runAnnotationTests(validated)
                if (testsRun == 0 && file.name.contains("-corpus/")) {
                    Assert.fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Transformed context was:\n" + writeToString(withoutInlineFunctions), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Transformed context was:\n" + writeToString(withoutInlineFunctions), e)
        }
    }
}
