import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.getNativeContext
import semlang.internal.test.runAnnotationTests
import semlang.parser.parseFile
import semlang.parser.validateContext
import java.io.File
import java.nio.file.Files
import jdk.nashorn.internal.objects.NativeFunction.call
import org.junit.Assert.fail
import semlang.api.ValidatedContext
import java.net.URLClassLoader
import java.util.*
import javax.tools.JavaCompiler.CompilationTask
import javax.tools.StandardLocation
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import javax.tools.JavaCompiler
import jdk.nashorn.internal.codegen.CompilerConstants.className
import sun.net.www.ParseUtil.toURI
import java.net.URL


@RunWith(Parameterized::class)
class WriteJavaTest(private val file: File) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            val corpusFolder = File("../semlang-corpus/src/main/semlang")
            val allFiles = corpusFolder.listFiles()
            return allFiles.map { file ->
                arrayOf(file as Any?)
            }
        }
    }

    @Test
    fun testWritingJava() {
        val context = validateContext(parseFile(file), listOf(getNativeContext()))

        val newSrcDir = Files.createTempDirectory("generatedJavaSource").toFile()
        val newTestSrcDir = Files.createTempDirectory("generatedJavaTestSource").toFile()
        val destFolder = Files.createTempDirectory("generatedJavaBin").toFile()

        val writtenJavaSourceInfo = writeJavaSourceIntoFolders(context, newSrcDir, newTestSrcDir)

        // Now run the compiler...
        val compiler = ToolProvider.getSystemJavaCompiler()

        val sourceFiles = collectNonDirFiles(listOf(newSrcDir, newTestSrcDir))


        val fileManager = compiler.getStandardFileManager(null, null, null)
        System.out.println("Source files: ${sourceFiles}")
        val compilationUnits = fileManager.getJavaFileObjectsFromFiles(sourceFiles)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(destFolder))

        val task = compiler.getTask(null, fileManager, null, null, null, compilationUnits)
        val success = task.call()!!
        if (!success) {
            fail("Couldn't compile the source")
        }
        System.out.println("Files in bin: ${collectNonDirFiles(listOf(destFolder))}")

        // TODO: So what do we do to check the correctness?
        // We'll need to either run a separate Java process, or load the created classes in a URLClassLoader
        // The end goal is to run any created functions and test that their outputs are correct...

        // Approach 1:
        // Turn each @Test into a JUnit test
        // Then do we run a JUnit process, or something else?
        // If we run JUnit in a separate process, how do we collect results?
        // We'll also need to know where those tests live, probably
        // (This might require the code-writing code above to tell us "here are the test names")
        // Can we have JUnit "add" tests to our current test?
//        val testClassName = "com.example.test.TestClass"

        val classDirUrl = destFolder.toURI().toURL()
        val ucl = URLClassLoader(arrayOf<URL>(classDirUrl))
        val testClasses = writtenJavaSourceInfo.testClassNames.map { testClassName ->
            Class.forName(testClassName, true, ucl)
        }
        System.out.println("testClasses: $testClasses")

        val result = org.junit.runner.JUnitCore.runClasses(*(testClasses.toTypedArray()))
        // TODO: Figure out a way to relay failures correctly
        if (!result.wasSuccessful()) {
            fail("Generated JUnit test was not successful. Failures were:\n" + result.failures)
        } else if (result.runCount == 0) {
            fail("Run count was 0")
        }
    }

    private fun collectNonDirFiles(rootDirs: List<File>): List<File> {
        val nonDirFiles = ArrayList<File>()

        val stack = ArrayDeque<File>(rootDirs)
        while (!stack.isEmpty()) {
            val curFile = stack.pop()
            if (curFile.isDirectory()) {
                curFile.listFiles().forEach { file ->
                    stack.push(file)
                }
            } else {
                nonDirFiles.add(curFile)
            }
        }
        return nonDirFiles
    }

}
