import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
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
        val sem1Context = translateSem2ContextToSem1(context, ModuleName("sem2", "testFile"))
        val validatedModule = validateModule(sem1Context, ModuleName("sem2", "testFile"), CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

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
