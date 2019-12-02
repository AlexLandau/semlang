package net.semlang.modules

import net.semlang.api.*
import net.semlang.api.parser.Issue
import net.semlang.api.parser.IssueLevel
import net.semlang.parser.*
import net.semlang.refill.*
import net.semlang.sem2.translate.collectTypesSummary
import net.semlang.sem2.translate.translateSem2ContextToSem1
import net.semlang.validator.*
import java.io.File
import java.lang.UnsupportedOperationException

sealed class ModuleDirectoryParsingResult {
    // TODO: We may want to go one step further and also include type information in here for the validator to use
    // (Counterpoint: If a dialect misreports its types, the validation may be inaccurate if that info is reused)
    data class Success(val module: UnvalidatedModule): ModuleDirectoryParsingResult()
    data class Failure(val errors: List<Issue>, val warnings: List<Issue>): ModuleDirectoryParsingResult()
}

enum class Dialect(val extensions: Set<String>, val needsTypeInfoToParse: Boolean) {
    Sem1(setOf("sem"), false) {
        override fun parseWithoutTypeInfo(file: File): ParsingResult {
            return net.semlang.parser.parseFile(file)
        }

        private fun toIR(result: ParsingResult): IR {
            return IR(result)
        }
        private fun fromIR(ir: IR): ParsingResult {
            return ir.value as ParsingResult
        }

        override fun parseToIR(documentUri: String, text: String): IR {
            return toIR(net.semlang.parser.parseString(text, documentUri))
        }

        override fun getTypesSummary(ir: IR, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): TypesSummary {
            val parsingResult = fromIR(ir)
            val context = when (parsingResult) {
                is ParsingResult.Success -> parsingResult.context
                is ParsingResult.Failure -> parsingResult.partialContext
            }
            return getTypesSummary(context, {})
        }

        override fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo, typesMetadata: TypesMetadata): ParsingResult {
            return fromIR(ir)
        }
    },
    Sem2(setOf("sem2"), true) {
        override fun parseWithoutTypeInfo(file: File): ParsingResult {
            throw UnsupportedOperationException()
        }

        override fun parseToIR(documentUri: String, text: String): IR {
            return toIR(net.semlang.sem2.parser.parseString(text, documentUri))
        }

        private fun toIR(parsingResult: net.semlang.sem2.parser.ParsingResult): IR {
            return IR(parsingResult)
        }
        private fun fromIR(ir: IR): net.semlang.sem2.parser.ParsingResult {
            return ir.value as net.semlang.sem2.parser.ParsingResult
        }

        override fun getTypesSummary(ir: IR, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): TypesSummary {
            val parsingResult = fromIR(ir)
            val context = when (parsingResult) {
                is net.semlang.sem2.parser.ParsingResult.Success -> parsingResult.context
                is net.semlang.sem2.parser.ParsingResult.Failure -> parsingResult.partialContext
            }
            return collectTypesSummary(context)
        }

        override fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo, typesMetadata: TypesMetadata): ParsingResult {
            val sem2Result = fromIR(ir)
            return when (sem2Result) {
                is net.semlang.sem2.parser.ParsingResult.Success -> {
                    translateSem2ContextToSem1(sem2Result.context, allTypesSummary, typesMetadata)
                }
                is net.semlang.sem2.parser.ParsingResult.Failure -> {
                    val sem2PartialTranslation = translateSem2ContextToSem1(sem2Result.partialContext, allTypesSummary, typesMetadata)
                    when (sem2PartialTranslation) {
                        is ParsingResult.Success -> ParsingResult.Failure(
                            sem2Result.errors,
                            sem2PartialTranslation.context
                        )
                        is ParsingResult.Failure -> ParsingResult.Failure(
                            sem2Result.errors + sem2PartialTranslation.errors,
                            sem2PartialTranslation.partialContext
                        )
                    }
                }
            }
        }
    },
    ;

    // Single-pass parsing API
    abstract fun parseWithoutTypeInfo(file: File): ParsingResult

    // Two-pass parsing API (IR type should be a single type per dialect)
    abstract fun parseToIR(documentUri: String, text: String): IR
    abstract fun getTypesSummary(ir: IR, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): TypesSummary
    abstract fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo, typesMetadata: TypesMetadata): ParsingResult

    /**
     * An intermediate representation used by the dialect to prevent needing to parse a file multiple times.
     */
    data class IR(val value: Any)
}

fun parseModuleDirectory(directory: File, repository: ModuleRepository): ModuleDirectoryParsingResult {
    return parseModuleDirectoryUsingTrickle(directory, repository)
//    val configFile = File(directory, "module.conf")
//    val parsedConfig = parseConfigFile(configFile)
//    return when (parsedConfig) {
//        is ModuleInfoParsingResult.Failure -> {
//            val error = Issue("Couldn't parse module.conf: ${parsedConfig.error.message}", null, IssueLevel.ERROR)
//            ModuleDirectoryParsingResult.Failure(listOf(error), listOf())
//        }
//        is ModuleInfoParsingResult.Success -> {
//            val moduleName = parsedConfig.info.name
//            val upstreamModules = listOf<ValidatedModule>() // TODO: Support dependencies
//
//            // TODO: Generalize to support "external" dialects
//            val allFiles = directory.listFiles()
//            val filesByDialect = sortByDialect(allFiles)
//
//            val combinedParsingResult = if (filesByDialect.keys.any { it.needsTypeInfoToParse }) {
//                // Collect type info in one pass, then parse in a second pass
//                collectParsingResultsTwoPasses(filesByDialect, moduleName, upstreamModules)
//            } else {
//                // Parse everything in one pass
//                collectParsingResultsSinglePass(filesByDialect)
//            }
//
//            when (combinedParsingResult) {
//                is ParsingResult.Success -> {
//                    ModuleDirectoryParsingResult.Success(UnvalidatedModule(parsedConfig.info, combinedParsingResult.context))
//                }
//                is ParsingResult.Failure -> {
//                    ModuleDirectoryParsingResult.Failure(combinedParsingResult.errors, listOf())
//                }
//            }
//        }
//    }

}

//fun collectParsingResultsTwoPasses(filesByDialect: Map<Dialect, List<File>>, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): ParsingResult {
//    val typesSummaries = ArrayList<TypesSummary>()
//    val irs = HashMap<File, Dialect.IR>()
//    for ((dialect, files) in filesByDialect.entries) {
//        for (file in files) {
//            try {
//                val ir = dialect.parseToIR(file.absolutePath, file.readText())
//                irs[file] = ir
//                typesSummaries.add(dialect.getTypesSummary(ir, moduleName, upstreamModules))
//            } catch (e: RuntimeException) {
//                throw RuntimeException("Error collecting types summary from file $file", e)
//            }
//        }
//    }
//
//    val allTypesSummary = combineTypesSummaries(typesSummaries)
//    // TODO: Actually support upstream modules
//    val moduleId = ModuleUniqueId(moduleName, "")
//    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
//    val recordIssue: (Issue) -> Unit = {} // TODO: Handle error case with conflicts across files
//    val allTypesInfo = getTypesInfoFromSummary(allTypesSummary, moduleId, upstreamModules, moduleVersionMappings, recordIssue)
//
//    val parsingResults = ArrayList<ParsingResult>()
//    for ((dialect, files) in filesByDialect.entries) {
//        for (file in files) {
//            try {
//                parsingResults.add(dialect.parseWithTypeInfo(irs[file]!!, allTypesInfo))
//            } catch (e: RuntimeException) {
//                throw RuntimeException("Error parsing file $file", e)
//            }
//
//        }
//    }
//    return combineParsingResults(parsingResults)
//}

//fun collectParsingResultsSinglePass(filesByDialect: Map<Dialect, List<File>>): ParsingResult {
//    val parsingResults = ArrayList<ParsingResult>()
//    for ((dialect, files) in filesByDialect.entries) {
//        for (file in files) {
//            try {
//                parsingResults.add(dialect.parseWithoutTypeInfo(file))
//            } catch (e: RuntimeException) {
//                throw RuntimeException("Error parsing file $file", e)
//            }
//        }
//    }
//    return combineParsingResults(parsingResults)
//}

//fun sortByDialect(allFiles: Array<out File>): Map<Dialect, List<File>> {
//    val results = HashMap<Dialect, MutableList<File>>()
//    for (file in allFiles) {
//        for (dialect in Dialect.values()) {
//            if (dialect.extensions.contains(file.extension)) {
//                if (!results.containsKey(dialect)) {
//                    results[dialect] = ArrayList()
//                }
//                results[dialect]!!.add(file)
//            }
//        }
//    }
//    return results
//}

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


fun parseModuleDirectoryUsingTrickle(directory: File, repository: ModuleRepository): ModuleDirectoryParsingResult {
    val configFile = File(directory, "module.conf")
    val definition = getFilesParsingDefinition(directory, repository)
    val instance = definition.instantiateSync()

    instance.setInput(CONFIG_TEXT, configFile.readText())

    for (file in directory.listFiles()) {
        if (file.name != "module.conf") {
            instance.setInputs(
                listOf(
                    // TODO: AddKey constructor would be nice here, or some kind of builder construct
                    TrickleInputChange.EditKeys(SOURCE_FILE_URLS, listOf(file.absolutePath), listOf()),
                    // TODO: Ditto for a singleton set-keyed constructor
                    TrickleInputChange.SetKeyed(SOURCE_TEXTS, mapOf(file.absolutePath to file.readText()))
                )
            )
        }
    }

    // TODO: Also handle the case where we get an error in config parsing
    return instance.getValue(MODULE_PARSING_RESULT)
}

// TODO: Support other dialects, probably by making dialect determination another asynchronous step in the process
// TODO: This maybe shouldn't be public long-term
fun determineDialect(filePath: String): Dialect? {
    if (filePath.endsWith(".sem") || filePath.endsWith(".sem1")) {
        return Dialect.Sem1
    }
    if (filePath.endsWith(".sem2")) {
        return Dialect.Sem2
    }
    return null
}