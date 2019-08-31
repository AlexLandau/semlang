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

        override fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo): ParsingResult {
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

        override fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo): ParsingResult {
            val sem2Result = fromIR(ir)
            return when (sem2Result) {
                is net.semlang.sem2.parser.ParsingResult.Success -> {
                    val sem1Context = translateSem2ContextToSem1(sem2Result.context, allTypesSummary)
                    ParsingResult.Success(sem1Context)
                }
                is net.semlang.sem2.parser.ParsingResult.Failure -> {
                    val sem1PartialContext = translateSem2ContextToSem1(sem2Result.partialContext, allTypesSummary)
                    ParsingResult.Failure(
                        sem2Result.errors,
                        sem1PartialContext
                    )
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
    abstract fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo): ParsingResult

    /**
     * An intermediate representation used by the dialect to prevent needing to parse a file multiple times.
     */
    data class IR(val value: Any)
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
            val moduleName = parsedConfig.info.name
            val upstreamModules = listOf<ValidatedModule>() // TODO: Support dependencies

            // TODO: Generalize to support "external" dialects
            val allFiles = directory.listFiles()
            val filesByDialect = sortByDialect(allFiles)

            val combinedParsingResult = if (filesByDialect.keys.any { it.needsTypeInfoToParse }) {
                // Collect type info in one pass, then parse in a second pass
                collectParsingResultsTwoPasses(filesByDialect, moduleName, upstreamModules)
            } else {
                // Parse everything in one pass
                collectParsingResultsSinglePass(filesByDialect)
            }

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

fun collectParsingResultsTwoPasses(filesByDialect: Map<Dialect, List<File>>, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): ParsingResult {
    val typesSummaries = ArrayList<TypesSummary>()
    val irs = HashMap<File, Dialect.IR>()
    for ((dialect, files) in filesByDialect.entries) {
        for (file in files) {
            try {
                val ir = dialect.parseToIR(file.absolutePath, file.readText())
                irs[file] = ir
                typesSummaries.add(dialect.getTypesSummary(ir, moduleName, upstreamModules))
            } catch (e: RuntimeException) {
                throw RuntimeException("Error collecting types summary from file $file", e)
            }
        }
    }

    val allTypesSummary = combineTypesSummaries(typesSummaries)
    // TODO: Actually support upstream modules
    val moduleId = ModuleUniqueId(moduleName, "")
    val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
    val recordIssue: (Issue) -> Unit = {} // TODO: Handle error case with conflicts across files
    val allTypesInfo = getTypesInfoFromSummary(allTypesSummary, moduleId, upstreamModules, moduleVersionMappings, recordIssue)

    val parsingResults = ArrayList<ParsingResult>()
    for ((dialect, files) in filesByDialect.entries) {
        for (file in files) {
            try {
                parsingResults.add(dialect.parseWithTypeInfo(irs[file]!!, allTypesInfo))
            } catch (e: RuntimeException) {
                throw RuntimeException("Error parsing file $file", e)
            }

        }
    }
    return combineParsingResults(parsingResults)
}

fun collectParsingResultsSinglePass(filesByDialect: Map<Dialect, List<File>>): ParsingResult {
    val parsingResults = ArrayList<ParsingResult>()
    for ((dialect, files) in filesByDialect.entries) {
        for (file in files) {
            try {
                parsingResults.add(dialect.parseWithoutTypeInfo(file))
            } catch (e: RuntimeException) {
                throw RuntimeException("Error parsing file $file", e)
            }
        }
    }
    return combineParsingResults(parsingResults)
}

fun sortByDialect(allFiles: Array<out File>): Map<Dialect, List<File>> {
    val results = HashMap<Dialect, MutableList<File>>()
    for (file in allFiles) {
        for (dialect in Dialect.values()) {
            if (dialect.extensions.contains(file.extension)) {
                if (!results.containsKey(dialect)) {
                    results[dialect] = ArrayList()
                }
                results[dialect]!!.add(file)
            }
        }
    }
    return results
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


fun parseModuleDirectoryUsingTrickle(directory: File, repository: ModuleRepository): ModuleDirectoryParsingResult {
    val configFile = File(directory, "module.conf")
    val definition = getFilesParsingDefinition()
    val instance = definition.instantiateSync()

    instance.setInput(CONFIG_TEXT, configFile.readText())

    for (file in directory.listFiles()) {
        instance.setInputs(listOf(
            // TODO: AddKey constructor would be nice here, or some kind of builder construct
            TrickleInputChange.EditKeys(SOURCE_FILE_URLS, listOf(file.absolutePath), listOf()),
            // TODO: Ditto for a singleton set-keyed constructor
            TrickleInputChange.SetKeyed(SOURCE_TEXTS, mapOf(file.absolutePath to file.readText()))
        ))
    }

    // TODO: Also handle the case where we get an error in config parsing
    return instance.getValue(MODULE_PARSING_RESULT)
}

internal val CONFIG_TEXT = NodeName<String>("configText")
internal val PARSED_CONFIG = NodeName<ModuleInfoParsingResult.Success>("parsedConfig")
internal val SOURCE_FILE_URLS = KeyListNodeName<String>("sourceFileUrls")
internal val SOURCE_TEXTS = KeyedNodeName<String, String>("sourceTexts")
internal val IRS = KeyedNodeName<String, Dialect.IR>("irs")
internal val TYPE_SUMMARIES = KeyedNodeName<String, TypesSummary>("typeSummaries")
internal val TYPE_INFO = NodeName<TypesInfo>("typeInfo")
internal val PARSING_RESULTS = KeyedNodeName<String, ParsingResult>("parsingResults")
internal val COMBINED_PARSING_RESULT = NodeName<ParsingResult>("combinedParsingResult")
internal val MODULE_PARSING_RESULT = NodeName<ModuleDirectoryParsingResult>("moduleParsingResult")
internal fun getFilesParsingDefinition(): TrickleDefinition {
    val builder = TrickleDefinitionBuilder()
    val stringClass = kotlin.String::class.java
    val configTextInput = builder.createInputNode(CONFIG_TEXT)
    val parsedConfig = builder.createNode(PARSED_CONFIG, configTextInput, { text ->
        // Parse the config
        parseConfigFileString(text) as ModuleInfoParsingResult.Success
    })
    val sourceFileUrls = builder.createKeyListInputNode(SOURCE_FILE_URLS)
    val sourceTexts = builder.createKeyedInputNode(SOURCE_TEXTS, sourceFileUrls)

    val irs = builder.createKeyedNode(IRS, sourceFileUrls, sourceTexts.keyedOutput(), { filePath: String, sourceText: String ->
        // Do something, make the IR
        val dialect = determineDialect(filePath)!!
        dialect.parseToIR(filePath, sourceText)
    })
    val typeSummaries = builder.createKeyedNode(TYPE_SUMMARIES, sourceFileUrls, irs.keyedOutput(), parsedConfig, { filePath, ir, config ->
        // Do something, make the type summary
        val dialect = determineDialect(filePath)!!
        val moduleName = config.info.name
        val upstreamModules = listOf<ValidatedModule>() // TODO: support
        dialect.getTypesSummary(ir, moduleName, upstreamModules)
    })

    val typeInfoNode = builder.createNode(TYPE_INFO, typeSummaries.fullOutput(), parsedConfig, { summaries, config ->
        val moduleName = config.info.name
        val upstreamModules = listOf<ValidatedModule>() // TODO: support
        val allTypesSummary = combineTypesSummaries(summaries)
        // TODO: Actually support upstream modules
        val moduleId = ModuleUniqueId(moduleName, "")
        val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
        val recordIssue: (Issue) -> Unit = {} // TODO: Handle error case with conflicts across files
        getTypesInfoFromSummary(allTypesSummary, moduleId, upstreamModules, moduleVersionMappings, recordIssue)
    })

    val parsingResultsNode = builder.createKeyedNode(PARSING_RESULTS, sourceFileUrls, irs.keyedOutput(), typeInfoNode, { filePath, ir, typeInfo ->
        val dialect = determineDialect(filePath)!!
        dialect.parseWithTypeInfo(ir, typeInfo)
    })

    val combinedParsingResultNode = builder.createNode(COMBINED_PARSING_RESULT, parsingResultsNode.fullOutput(), { parsingResults ->
        combineParsingResults(parsingResults)
    })

    val finalResultNode = builder.createNode(MODULE_PARSING_RESULT, parsedConfig, combinedParsingResultNode, { config, combinedParsingResult ->
        when (combinedParsingResult) {
            is ParsingResult.Success -> {
                ModuleDirectoryParsingResult.Success(UnvalidatedModule(config.info, combinedParsingResult.context))
            }
            is ParsingResult.Failure -> {
                ModuleDirectoryParsingResult.Failure(combinedParsingResult.errors, listOf())
            }
        }
    }, { error ->
        ModuleDirectoryParsingResult.Failure(listOf(), listOf())
    })

    return builder.build()
}

// TODO: Support other dialects, probably by making dialect determination another asynchronous step in the process
private fun determineDialect(filePath: String): Dialect? {
    if (filePath.endsWith(".sem") || filePath.endsWith(".sem1")) {
        return Dialect.Sem1
    }
    if (filePath.endsWith(".sem2")) {
        return Dialect.Sem2
    }
    return null
}