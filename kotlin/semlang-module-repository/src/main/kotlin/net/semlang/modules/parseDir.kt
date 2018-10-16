package net.semlang.modules

import net.semlang.parser.*
import net.semlang.validator.Issue
import net.semlang.validator.IssueLevel
import net.semlang.validator.ValidationResult
import net.semlang.validator.validate
import java.io.File

sealed class ModuleDirectoryParsingResult {
    data class Success(val module: UnvalidatedModule): ModuleDirectoryParsingResult()
    data class Failure(val errors: List<Issue>, val warnings: List<Issue>): ModuleDirectoryParsingResult()
}

fun parseModuleDirectory(directory: File, repository: ModuleRepository): ModuleDirectoryParsingResult {
    val configFile = File(directory, "module.conf")
    val parsedConfig = parseConfigFile(configFile)
    return when (parsedConfig) {
        is ModuleInfoParsingResult.Failure -> {
            // TODO: This is for debugging, do we want it generally?
            parsedConfig.error.printStackTrace()
            val error = Issue("Couldn't parse module.conf: ${parsedConfig.error.message}", null, IssueLevel.ERROR)
            ModuleDirectoryParsingResult.Failure(listOf(error), listOf())
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

            when (combinedParsingResult) {
                is ParsingResult.Success -> {
                    ModuleDirectoryParsingResult.Success(UnvalidatedModule(parsedConfig.info, combinedParsingResult.context))
                }
                is ParsingResult.Failure -> {
                    ModuleDirectoryParsingResult.Failure(combinedParsingResult.errors, listOf())
                }
            }
        }
    }

}

// TODO: Rewrite in terms of the other function
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
                val uniqueId = repository.getModuleUniqueId(dependencyId, directory)
                repository.loadModule(uniqueId)
            }

            validate(combinedParsingResult, parsedConfig.info.name, nativeModuleVersion, dependencies)
        }
    }
}
