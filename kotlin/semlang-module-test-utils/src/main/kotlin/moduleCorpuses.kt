package net.semlang.internal.test

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ValidatedModule
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.modules.parseAndValidateModuleDirectory
import java.io.File

/**
 * First argument: Standalone compilable file as a File
 * Second argument: List of ValidatedModules to be used as libraries
 */
fun getCompilableFilesWithAssociatedLibraries(): Collection<Array<Any?>> {
    val compilerTestsFolder = File("../../semlang-parser-tests/pass")
    val corpusFolder = File("../../semlang-corpus/src/main/semlang")
    val standardLibraryCorpusFolder = File("../../semlang-library-corpus/src/main/semlang")

    val allResults = ArrayList<Array<Any?>>()

    val allStandaloneFiles = compilerTestsFolder.listFiles() + corpusFolder.listFiles()
    allResults.addAll(allStandaloneFiles.map { file ->
        arrayOf<Any?>(file, listOf<Any?>())
    })

    val standardLibrary: ValidatedModule = validateStandardLibraryModule()
    allResults.addAll(standardLibraryCorpusFolder.listFiles().map { file ->
        arrayOf<Any?>(file, listOf(standardLibrary))
    })

    return allResults
}

fun validateStandardLibraryModule(): ValidatedModule {
    val standardLibraryFolder = File("../../semlang-library/src/main/semlang")
    return parseAndValidateModuleDirectory(standardLibraryFolder, CURRENT_NATIVE_MODULE_VERSION, getDefaultLocalRepository()).assumeSuccess()
}
