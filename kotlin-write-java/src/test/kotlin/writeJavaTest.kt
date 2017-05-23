import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import semlang.api.getNativeContext
import semlang.parser.parseFile
import semlang.parser.validateContext
import java.io.File
import java.nio.file.Files
import org.junit.Assert.fail
import java.net.URLClassLoader
import java.util.*
import javax.tools.StandardLocation
import javax.tools.ToolProvider
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
        // TODO: Get from writtenJavaSourceInfo
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
        System.out.println("Here are the source files:")
        for (sourceFile in sourceFiles) {
            System.out.println("Generated code for test " + file + ":")
            System.out.println(Files.readAllLines(sourceFile.toPath()).joinToString("\n"));
        }

        // Load the class files we just compiled
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
