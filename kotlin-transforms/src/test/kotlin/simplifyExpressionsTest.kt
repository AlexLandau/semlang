import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.getNativeContext
import semlang.parser.parseFile
import semlang.parser.validateContext
import semlang.transforms.simplifyExpressions
import java.io.File

@RunWith(Parameterized::class)
class SimplifyExpressionsTest(private val file: File) {
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
        val context = validateContext(parseFile(file), listOf(getNativeContext()))

        // TODO: Check that the semantics of any examples are actually unchanged, according to our interpreter
        // (This requires storing inputs to try with each given code sample; ideally we'd combine this
        // with storing the expected outputs, so interpreter tests can use the same framework)
        val simplified = simplifyExpressions(context)
        // TODO: Test sem0 output and parsing round-trip
    }

}
