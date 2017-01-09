import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.ValidatedContext
import semlang.parser.Try
import semlang.parser.parseFile
import semlang.parser.validateContext
import java.io.File

@RunWith(Parameterized::class)
class ValidatorPositiveTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("src/test/semlang/validatorTests/pass")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val result = parseAndValidateFile(file)
        result.assume()
    }
}

@RunWith(Parameterized::class)
class ValidatorNegativeTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("src/test/semlang/validatorTests/fail")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val result = parseAndValidateFile(file)
        result.ifGood<Any> {
            throw AssertionError("File ${file.absolutePath} should have failed validation, but passed")
        }
    }
}

private fun parseAndValidateFile(file: File): Try<ValidatedContext> {
    val functionsMap2 = parseFile(file)
    return validateContext(functionsMap2)
}
