package net.semlang.tssupport.test

import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.api.*
import net.semlang.parser.parseAndValidateFile
import net.semlang.parser.toJsonText
import org.junit.Ignore
import java.io.File
import java.util.concurrent.TimeUnit

// Until we have proper parsing in JS, this is the easiest way to run these tests
@Ignore // TODO: Remove this once the replacement is in place
@RunWith(Parameterized::class)
class TypescriptInterpreterTests(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val folder = File("../../semlang-corpus/src/main/semlang")
            return folder.listFiles().map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun test() {
        val validatedModule = parseAndValidateFile(file)

        // Convert these to JS files...
        val jsonText = toJsonText(validatedModule)

        val tempFile = File.createTempFile("semlangJson", ".js")
        tempFile.writeText(jsonText)

        // TODO: Doing this through Gradle is pretty slow (JVM startup times and all that). Could we find the Node
        // executable directly somehow? Or use some other strategy for the JSON translation?
        val gradleWrapper = if (System.getProperty("os.name").startsWith("Windows")) {
            "..\\..\\gradlew.bat"
        } else {
            "../../gradlew"
        }
        val process = ProcessBuilder(gradleWrapper, ":typescript:semlang-api:runInterpreterTests", "-Pargument=${tempFile.absolutePath}")
                .start()
        val standardOutThread = Thread({
            while (process.isAlive && !Thread.currentThread().isInterrupted()) {
                process.inputStream.copyTo(System.out)
            }
        })
        standardOutThread.start()
        val standardErrThread = Thread({
            while (process.isAlive && !Thread.currentThread().isInterrupted()) {
                process.errorStream.copyTo(System.err)
            }
        })
        standardErrThread.start()

        val hasExited = process.waitFor(30, TimeUnit.SECONDS)
        if (!hasExited) {
            process.destroy()
            fail("Gradle/node process has not exited after 30 seconds")
        }
        if (process.exitValue() != 0) {
            fail("Test failed; see output for error messages")
        }
    }
}

private fun parseAndValidateFile(file: File): ValidatedModule {
    return parseAndValidateFile(file, ModuleId("semlang", "corpusFile", "0.0.1"), CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()
}
