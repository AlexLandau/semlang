package net.semlang.internal.test

import java.io.File

/**
 * Returns the files in the semlang standard library corpus in the format used by the JUnit
 * Parameterized test runner. If this is used as the returned value of the data() method, it
 * should be paired with a constructor taking a File as the lone argument.
 *
 * Files in the standard library corpus are expected to be compiled against the standard library
 * and should contain @Test annotations.
 */
fun getSemlangStandardLibraryCorpusFiles(): Collection<Array<Any?>> {
    val folder = File("../../semlang-library-corpus/src/main/semlang")
    return folder.listFiles().map { file ->
        arrayOf<Any?>(file)
    }
}

/**
 * Returns the files in the semlang native corpus in the format used by the JUnit
 * Parameterized test runner. If this is used as the returned value of the data() method, it
 * should be paired with a constructor taking a File as the lone argument.
 *
 * Files in the native corpus can be compiled by themselves and should contain @Test annotations.
 */
fun getSemlangNativeCorpusFiles(): Collection<Array<Any?>> {
    val folder = File("../../semlang-corpus/src/main/semlang")
    return folder.listFiles().map { file ->
        arrayOf<Any?>(file)
    }
}

/**
 * Returns test source files that can be individually compiled in the format used by the JUnit
 * Parameterized test runner. If this is used as the returned value of the data() method, it
 * should be paired with a constructor taking a File as the lone argument.
 *
 * These files can be compiled by themselves and may or may not contain @Test annotations.
 */
fun getAllStandaloneCompilableFiles(): Collection<Array<Any?>> {
    val compilerTestsFolder = File("../../semlang-parser-tests/pass")
    val corpusFolder = File("../../semlang-corpus/src/main/semlang")
    val allFiles = compilerTestsFolder.listFiles() + corpusFolder.listFiles()
    return allFiles.map { file ->
        arrayOf<Any?>(file)
    }
}
