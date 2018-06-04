import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.linker.linkModuleWithDependencies
import net.semlang.modules.parser.parseAndValidateModuleDirectory
import net.semlang.parser.parseAndValidateFile
import net.semlang.parser.toJsonText
import net.semlang.parser.validateModule
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
        val module = parseAndValidateFile(sourceFile, ModuleId("semlang-test", sourceFile.nameWithoutExtension, "develop"), CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()

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

    val standardLibrary = parseAndValidateModuleDirectory(File("../../semlang-library/src/main/semlang"), CURRENT_NATIVE_MODULE_VERSION).assumeSuccess()

    for (sourceFile in sourcesDir.listFiles()) {
        val module = parseAndValidateFile(sourceFile, ModuleId("semlang-test", sourceFile.nameWithoutExtension, "develop"), CURRENT_NATIVE_MODULE_VERSION, listOf(standardLibrary)).assumeSuccess()
        val linkedContext = linkModuleWithDependencies(module)
        val linkedModule = validateModule(linkedContext.contents, linkedContext.info.id, CURRENT_NATIVE_MODULE_VERSION, listOf()).assumeSuccess()

        val jsonText = toJsonText(linkedModule)

        val outputFile = File(outputDir, sourceFile.name)
        outputFile.writeText(jsonText)
    }
}
