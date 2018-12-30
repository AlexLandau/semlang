package net.semlang.sem2.translate

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import net.semlang.internal.test.runAnnotationTests
import net.semlang.parser.writeToString
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.sem2.parser.parseFile
import net.semlang.sem2.translate.translateSem2ContextToSem1
import net.semlang.validator.validateModule
import java.io.File

@RunWith(Parameterized::class)
class Sem2ToSem1Test(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return File("../../sem2-corpus").listFiles().map { file ->
                arrayOf<Any?>(file)
            }
        }
    }

    @Test
    fun testTranslation() {
        val context = parseFile(file).assumeSuccess()
        val sem1Context = translateSem2ContextToSem1(context, ModuleName("sem2", "testFile"), listOf())
        val validatedModule = try {
            validateModule(sem1Context, ModuleName("sem2", "testFile"), CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        } catch (e: RuntimeException) {
            throw AssertionError("Validation error; translated sem1 context was: ${writeToString(sem1Context)}", e)
        }

        try {
            try {
                val testsRun = runAnnotationTests(validatedModule)
                if (testsRun == 0 /*&& file.name.contains("/semlang-corpus/")*/) {
                    fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(validatedModule), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(validatedModule), e)
        }
    }

}

/**
 * Checks that valid sem1 source files are also valid sem2 source files.
 */
@RunWith(Parameterized::class)
class Sem1AsSem2Test(private val file: File, private val dependencies: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testTranslation() {
        val context = parseFile(file).assumeSuccess()
        val sem1Context = translateSem2ContextToSem1(context, ModuleName("sem2", "testFile"), dependencies)
        val validatedModule = try {
            validateModule(sem1Context, ModuleName("sem2", "testFile"), CURRENT_NATIVE_MODULE_VERSION, dependencies).assumeSuccess()
        } catch (e: RuntimeException) {
            throw AssertionError("Validation error; translated sem1 context was: ${writeToString(sem1Context)}", e)
        }

        try {
            try {
                val testsRun = runAnnotationTests(validatedModule)
                if (testsRun == 0 && !file.path.contains("/semlang-parser/")) {
                    fail("Found no @Test annotations in corpus file $file")
                }
            } catch (e: AssertionError) {
                throw AssertionError("Simplified context was:\n" + writeToString(validatedModule), e)
            }
        } catch (e: RuntimeException) {
            throw RuntimeException("Simplified context was:\n" + writeToString(validatedModule), e)
        }
    }

}
