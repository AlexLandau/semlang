package net.semlang.writejava.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import net.semlang.internal.test.TestsType
import net.semlang.internal.test.getCompilableFilesWithAssociatedLibraries
import net.semlang.internal.test.getExpectedTestCount
import net.semlang.linker.linkModuleWithDependencies
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import net.semlang.parser.parseFile
import net.semlang.validator.validateModule
import net.semlang.writejava.writeJavaSourceIntoFolders
import java.io.File
import java.nio.file.Files
import org.junit.Assert.fail
import java.net.URLClassLoader
import java.util.*
import javax.tools.StandardLocation
import javax.tools.ToolProvider
import java.net.URL


@RunWith(Parameterized::class)
class WriteJavaTest(private val file: File, private val libraries: List<ValidatedModule>) {
    companion object ParametersSource {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun data(): Collection<Array<Any?>> {
            return getCompilableFilesWithAssociatedLibraries()
        }
    }

    @Test
    fun testWritingJava() {
        val unlinkedModule = validateModule(parseFile(file).assumeSuccess(), ModuleId("semlang", "testFile", "devTest"), CURRENT_NATIVE_MODULE_VERSION, libraries).assumeSuccess()
        val linkedModule = if (libraries.isEmpty()) {
            unlinkedModule
        } else {
            val linkedContext = linkModuleWithDependencies(unlinkedModule)
            validateModule(linkedContext.contents, linkedContext.info.id, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()
        }

        val newSrcDir = Files.createTempDirectory("generatedJavaSource").toFile()
        val newTestSrcDir = Files.createTempDirectory("generatedJavaTestSource").toFile()
        val destFolder = Files.createTempDirectory("generatedJavaBin").toFile()

        val writtenJavaSourceInfo = writeJavaSourceIntoFolders(linkedModule, listOf("net", "semlang", "test"), newSrcDir, newTestSrcDir)

        // Now run the compiler...
        val compiler = ToolProvider.getSystemJavaCompiler()
        // TODO: Get from writtenJavaSourceInfo
        val sourceFiles = collectNonDirFiles(listOf(newSrcDir, newTestSrcDir))
        val fileManager = compiler.getStandardFileManager(null, null, Charsets.UTF_8)
        System.out.println("Source files: $sourceFiles")
        val compilationUnits = fileManager.getJavaFileObjectsFromFiles(sourceFiles)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(destFolder))

        System.out.println("Files in bin: ${collectNonDirFiles(listOf(destFolder))}")
        System.out.println("Here are the source files:")
        for (sourceFile in sourceFiles) {
            System.out.println("Generated code for test $file (file $sourceFile):")
            System.out.println(Files.readAllLines(sourceFile.toPath()).joinToString("\n"))
        }

        val task = compiler.getTask(null, fileManager, null, null, null, compilationUnits)
        val success = task.call()!!
        if (!success) {
            fail("Couldn't compile the source")
        }

        // Load the class files we just compiled
        val classDirUrl = destFolder.toURI().toURL()
        val ucl = URLClassLoader(arrayOf<URL>(classDirUrl))
        val testClasses = writtenJavaSourceInfo.testClassNames.map { testClassName ->
            Class.forName(testClassName, true, ucl)
        }
        System.out.println("testClasses: $testClasses")

        val result = org.junit.runner.JUnitCore.runClasses(*(testClasses.toTypedArray()))
        val expectedRunCount = getExpectedTestCount(linkedModule, TestsType.NON_MOCK_TESTS)
        // TODO: Figure out a way to relay failures correctly
        if (!result.wasSuccessful()) {
            fail("Generated JUnit test was not successful. Failures were:\n" + result.failures)
        } else if (result.runCount != expectedRunCount) {
            fail("Expected number of tests to run was $expectedRunCount, actual was ${result.runCount}")
        }
    }

    private fun collectNonDirFiles(rootDirs: List<File>): List<File> {
        val nonDirFiles = ArrayList<File>()

        val stack = ArrayDeque<File>(rootDirs)
        while (!stack.isEmpty()) {
            val curFile = stack.pop()
            if (curFile.isDirectory()) {
                for (file in curFile.listFiles()) {
                    stack.push(file)
                }
            } else {
                nonDirFiles.add(curFile)
            }
        }
        return nonDirFiles
    }

}
