package net.semlang.support.typescript

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.linker.linkModuleWithDependencies
import net.semlang.modules.getDefaultLocalRepository
import net.semlang.modules.parseAndValidateModuleDirectory
import net.semlang.parser.toJsonText
import net.semlang.validator.parseAndValidateFile
import net.semlang.validator.validateModule
import java.io.File

fun main(args: Array<String>) {
    translateNativeCorpus()
    linkAndTranslateStandardLibraryCorpus()
}

private fun translateNativeCorpus() {
    val corpusDir = File("../../semlang-corpus")
    if (!corpusDir.isDirectory) {
        error("Running from the wrong directory")
    }
    val sourcesDir = File(corpusDir, "src/main/semlang")
    if (!sourcesDir.isDirectory) {
        error("Couldn't find sources directory")
    }
    val outputDir = File(corpusDir, "build/translations/json")
    if (outputDir.exists()) {
        outputDir.deleteRecursively()
    }
    outputDir.mkdirs()

    for (sourceFile in sourcesDir.listFiles()) {
        val module = parseAndValidateFile(sourceFile, ModuleName("semlang-test", sourceFile.nameWithoutExtension), CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()

        val jsonText = toJsonText(module)

        val outputFile = File(outputDir, sourceFile.name)
        outputFile.writeText(jsonText)
    }
}

fun linkAndTranslateStandardLibraryCorpus() {
    val corpusDir = File("../../semlang-library-corpus")
    if (!corpusDir.isDirectory) {
        error("Running from the wrong directory")
    }
    val sourcesDir = File(corpusDir, "src/main/semlang")
    if (!sourcesDir.isDirectory) {
        error("Couldn't find sources directory")
    }
    val outputDir = File(corpusDir, "build/translations/json")
    if (outputDir.exists()) {
        outputDir.deleteRecursively()
    }
    outputDir.mkdirs()

    val standardLibrary = parseAndValidateModuleDirectory(File("../../semlang-library/src/main/semlang"), CURRENT_NATIVE_MODULE_VERSION, getDefaultLocalRepository()).assumeSuccess()

    for (sourceFile in sourcesDir.listFiles()) {
        val module = parseAndValidateFile(sourceFile, ModuleName("semlang-test", sourceFile.nameWithoutExtension), CURRENT_NATIVE_MODULE_VERSION, listOf(standardLibrary)).assumeSuccess()
        val linkedContext = linkModuleWithDependencies(module)
        val linkedModule = validateModule(linkedContext.contents, linkedContext.info.name, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

        val jsonText = toJsonText(linkedModule)

        val outputFile = File(outputDir, sourceFile.name)
        outputFile.writeText(jsonText)
    }
}
