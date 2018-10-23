package net.semlang.modules

import net.semlang.parser.*
import net.semlang.validator.*
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

fun parseAndValidateModuleDirectory(directory: File, nativeModuleVersion: String, repository: ModuleRepository): ValidationResult {
    val dirParseResult = parseModuleDirectory(directory, repository)
    return when (dirParseResult) {
        is ModuleDirectoryParsingResult.Success -> {
            val module = dirParseResult.module

            val dependencies = module.info.dependencies.map { dependencyId ->
                val uniqueId = repository.getModuleUniqueId(dependencyId, directory)
                repository.loadModule(uniqueId)
            }

            validateModule(module.contents, module.info.name, nativeModuleVersion, dependencies)
        }
        is ModuleDirectoryParsingResult.Failure -> {
            ValidationResult.Failure(dirParseResult.errors, dirParseResult.warnings)
        }
    }
}
