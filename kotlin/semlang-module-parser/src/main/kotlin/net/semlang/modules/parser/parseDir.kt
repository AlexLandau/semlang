package net.semlang.modules.parser

import net.semlang.parser.*
import java.io.File

fun parseAndValidateModuleDirectory(directory: File, nativeModuleVersion: String): ValidationResult {
    val configFile = File(directory, "module.conf")
    val parsedConfig = parseConfigFile(configFile)
    return when (parsedConfig) {
        is ModuleInfoParsingResult.Failure -> {
            val error = Issue("Couldn't parse module.conf: ${parsedConfig.error.message}", null, IssueLevel.ERROR)
            ValidationResult.Failure(listOf(error), listOf())
        }
        is ModuleInfoParsingResult.Success -> {
            val semFiles = directory.listFiles { dir, name -> name.endsWith(".sem") }
            val parsingResults = semFiles.map { file ->
                try {
                    parseFile(file)
                } catch (e: RuntimeException) {
                    throw RuntimeException("Error parsing file $file", e)
                }
            }
            val combinedParsingResult = combineParsingResults(parsingResults)

            // TODO: Dependencies should figure in here at some point...
            validate(combinedParsingResult, parsedConfig.info.id, nativeModuleVersion, listOf())
        }
    }
}
