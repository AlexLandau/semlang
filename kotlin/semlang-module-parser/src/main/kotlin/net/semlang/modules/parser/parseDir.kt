package net.semlang.modules.parser

import net.semlang.modules.ModuleRepository
import net.semlang.parser.*
import net.semlang.validator.Issue
import net.semlang.validator.IssueLevel
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import java.io.File

fun parseAndValidateModuleDirectory(directory: File, nativeModuleVersion: String, repository: ModuleRepository): ValidationResult {
    val configFile = File(directory, "module.conf")
    val parsedConfig = parseConfigFile(configFile)
    return when (parsedConfig) {
        is ModuleInfoParsingResult.Failure -> {
            // TODO: This is for debugging, do we want it generally?
            parsedConfig.error.printStackTrace()
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

            val dependencies = parsedConfig.info.dependencies.map { dependencyId ->
                repository.loadModule(dependencyId)
            }

            // TODO: Dependencies should figure in here at some point...
            validate(combinedParsingResult, parsedConfig.info.id, nativeModuleVersion, dependencies)
        }
    }
}
