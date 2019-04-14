package net.semlang.modules

import net.semlang.api.*
import net.semlang.api.parser.Issue
import net.semlang.api.parser.IssueLevel
import net.semlang.parser.*
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

        override fun parseToIR(file: File): IR {
            return toIR(net.semlang.parser.parseFile(file))
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

        override fun parseToIR(file: File): IR {
            return toIR(net.semlang.sem2.parser.parseFile(file))
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
    abstract fun parseToIR(file: File): IR
    abstract fun getTypesSummary(ir: IR, moduleName: ModuleName, upstreamModules: List<ValidatedModule>): TypesSummary
    abstract fun parseWithTypeInfo(ir: IR, allTypesSummary: TypesInfo): ParsingResult

    /**
     * An intermediate representation used by the dialect to prevent needing to parse a file multiple times.
     */
    data class IR(val value: Any)
}

/*
  Save this for later...

// TODO: Also record the state of the file itself, and a checksum of the combined TypesSummary used for parsing
// Note: Variables in this class are guarded by synchronization on the FilesParsingState instance
private class PartialFileParsingState(
    val contents: String,
    val dialect: Dialect,
    var ir: Dialect.IR?,
    var typesSummary: TypesSummary?,
    var typesInfoUsedInParsing: TypesInfo? = null,
    var parsingResult: ParsingResult?
)

// TODO: "Constructor" that forces you to set the configFileContents (?)
// TODO: I would love to have a framework for writing code like this nicely (i.e., where operations record their intermediate
//  results and you can update one input without unnecessary work)... and Semlang would be a great language to make that work in
class FilesParsingState {
    private var configText: String = ""
    private var curConfigResult: ModuleInfoParsingResult? = null
    // This one seems like it would be better with timestamps...
    private var combinedTypesInfoBasis: List<TypesSummary> = listOf()
    // TODO: We're going to be comparing this by reference for now, which kind of sucks
    private var combinedTypesInfo: TypesInfo? = null
    /**
     * The key is the filePath.
     */
    private val sourceResults: MutableMap<String, PartialFileParsingState> = LinkedHashMap()

    @Synchronized fun getNextStepsOrResult(): PartialParsingResult {
        val configResult = curConfigResult
        if (configResult == null) {
            return PartialParsingResult.Incomplete(listOf(ParsingStep.ParseConfig(configText)))
        }
        return when (configResult) {
            is ModuleInfoParsingResult.Failure -> {
                val error = Issue("Couldn't parse module.conf: ${configResult.error.message}", null, IssueLevel.ERROR)
                PartialParsingResult.Complete(ModuleDirectoryParsingResult.Failure(listOf(error), listOf()))
            }
            is ModuleInfoParsingResult.Success -> {
                val moduleName = configResult.info.name
                val upstreamModules = listOf<ValidatedModule>() // TODO: Support dependencies

                val neededSteps = ArrayList<ParsingStep<out Any>>()

                var anyTypesSummaryMissing = false
                for ((filePath, partialState) in sourceResults) {
                    if (partialState.typesSummary == null) {
                        anyTypesSummaryMissing = true
                    }

                    if (partialState.ir == null) {
                        neededSteps.add(ParsingStep.ParseIr(filePath, partialState.contents, partialState.dialect))
                    } else if (partialState.typesSummary == null) {
                        neededSteps.add(ParsingStep.SummarizeTypes(filePath,
                            partialState.ir!!, partialState.dialect, moduleName, upstreamModules))
                    }
                }

                if (anyTypesSummaryMissing) {
                    if (neededSteps.isEmpty()) error("Missing types summaries should result in needed steps")
                    return PartialParsingResult.Incomplete(neededSteps)
                }

                val typesInfoBasis = sourceResults.values.map { it.typesSummary!! }
                if (combinedTypesInfo == null || typesInfoBasis != combinedTypesInfoBasis) {
                    return PartialParsingResult.Incomplete(listOf(ParsingStep.CombineTypes(typesInfoBasis)))
                }

                for ((filePath, partialState) in sourceResults) {
                    if (partialState.parsingResult == null || )
                }
            }
        }
    }

    @Synchronized fun reportConfigFileChanged(configFileContents: String) {
        curConfigResult = null
        configText = configFileContents
    }

    @Synchronized fun reportSourceFileAddedOrChanged(filePath: String, contents: String) {
        val dialect = determineDialect(filePath)
        if (dialect == null) {
            sourceResults.remove(filePath)
            return
        }
        sourceResults[filePath] = PartialFileParsingState(contents, dialect, null, null, null)
    }

    @Synchronized fun reportSourceFileRemoved(filePath: String) {
        sourceResults.remove(filePath)
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

    @Synchronized fun <T, S: ParsingStep<T>> reportStepResult(stepResult: StepResult<T, S>) {
        val step: ParsingStep<T> = stepResult.step
        val result = stepResult.result
        val unused = when (step) {
            is ParsingStep.ParseConfig -> reportStepResultParseConfig(step, result as ModuleInfoParsingResult)
            is ParsingStep.ParseIr -> reportStepResultParseIr(step, result as Dialect.IR)
            is ParsingStep.SummarizeTypes -> reportStepResultSummarizeTypes(step, result as TypesSummary)
            is ParsingStep.CombineTypes -> reportStepResultCombineTypes(step, result as TypesInfo)
            is ParsingStep.Parse -> reportStepResultParse(step, result as ParsingResult)
        }
    }

    private fun reportStepResultCombineTypes(step: ParsingStep.CombineTypes, typesInfo: TypesInfo) {
        if (step.typesInfoBasis != combinedTypesInfoBasis) {
            return
        }
        this.combinedTypesInfo = typesInfo
    }

    private fun reportStepResultSummarizeTypes(step: ParsingStep.SummarizeTypes, typesSummary: TypesSummary) {
        val curResults = this.sourceResults[step.filePath]
        if (curResults == null) {
            // We're no longer tracking the file; ignore this result
            return
        }
        if (step.ir != curResults.ir) {
            // File has been updated since
            return
        }
        curResults.typesSummary = typesSummary
    }

    private fun reportStepResultParseIr(step: ParsingStep.ParseIr, ir: Dialect.IR) {
        val curResults = this.sourceResults[step.filePath]
        if (curResults == null) {
            // We're no longer tracking the file; ignore this result
            return
        }
        if (step.sourceText != curResults.contents) {
            // File has been updated since
            return
        }
        curResults.ir = ir
    }

    private fun reportStepResultParseConfig(step: ParsingStep.ParseConfig, result: ModuleInfoParsingResult) {
        if (configText != step.configText) {
            // The config has been updated since this; discard the result
            return
        }
        curConfigResult = result
    }

}

fun doParsingSynchronously(folder: File): ModuleDirectoryParsingResult {
    val state = FilesParsingState()
    state.reportConfigFileChanged(folder.resolve("module.conf").readText())
    for (file in folder.listFiles()) {
        if (file.name != "module.conf") {
            state.reportSourceFileAddedOrChanged(file.path, file.readText())
        }
    }
    while (true) {
        val output = state.getNextStepsOrResult()
        val unused = when (output) {
            is PartialParsingResult.Complete -> {
                return output.result
            }
            is PartialParsingResult.Incomplete -> {
                for (step in output.nextSteps) {
                    val stepResult = step.execute()
                    state.reportStepResult(stepResult)
                }
            }
        }
    }
}

sealed class PartialParsingResult {
    class Complete(val result: ModuleDirectoryParsingResult): PartialParsingResult()
    class Incomplete(val nextSteps: List<ParsingStep<out Any>>): PartialParsingResult()
}

// TODO: It would be nice if we could make these lighter-weight; something something weak references and a flyweight cache to map context keys to contexts
sealed class ParsingStep<T> {
    abstract fun execute(): StepResult<T, ParsingStep<T>>
    data class ParseConfig(val configText: String): ParsingStep<ModuleInfoParsingResult>() {
        override fun execute(): StepResult<ModuleInfoParsingResult, ParsingStep<ModuleInfoParsingResult>> {
            val parsedConfig = parseConfigFileString(configText)
            return StepResult(this, parsedConfig)
        }
    }

    data class ParseIr(val filePath: String, val sourceText: String, val dialect: Dialect): ParsingStep<Dialect.IR>() {
        override fun execute(): StepResult<Dialect.IR, ParsingStep<Dialect.IR>> {
            val ir = dialect.parseToIR(sourceText)
            return StepResult(this, ir)
        }
    }

    data class SummarizeTypes(val filePath: String, val ir: Dialect.IR, val dialect: Dialect, val moduleName: ModuleName, val upstreamModules: List<ValidatedModule>): ParsingStep<TypesSummary>() {
        override fun execute(): StepResult<TypesSummary, ParsingStep<TypesSummary>> {
            val typesSummary = dialect.getTypesSummary(ir, moduleName, upstreamModules)
            return StepResult(this, typesSummary)
        }
    }

    data class CombineTypes(val typesInfoBasis: List<TypesSummary>, val moduleName: ModuleName, val upstreamModules: List<ValidatedModule>) : ParsingStep<TypesInfo>() {
        override fun execute(): StepResult<TypesInfo, ParsingStep<TypesInfo>> {
            val allTypesSummary = combineTypesSummaries(typesInfoBasis)
            // TODO: Actually support upstream modules
            val moduleId = ModuleUniqueId(moduleName, "")
            val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
            val recordIssue: (Issue) -> Unit = {} // TODO: Handle error case with conflicts across files
            val allTypesInfo = getTypesInfoFromSummary(allTypesSummary, moduleId, upstreamModules, moduleVersionMappings, recordIssue)
            return StepResult(this, allTypesInfo)
        }
    }

    data class Parse(val filePath: String, val ir: Dialect.IR, val dialect: Dialect, val allTypesInfo: TypesInfo): ParsingStep<ParsingResult>() {
        override fun execute(): StepResult<ParsingResult, ParsingStep<ParsingResult>> {
            val result = dialect.parseWithTypeInfo(ir, allTypesInfo)
            return StepResult(this, result)
        }
    }
}

class StepResult<T, S: ParsingStep<T>>(val step: S, val result: T)
 */

//fun combineParsingResults(filesParsingState: FilesParsingState): PartialParsingResult {
//    val configResult = filesParsingState.configResult
//    return when (configResult) {
//        is ModuleInfoParsingResult.Failure -> {
//            val error = Issue("Couldn't parse module.conf: ${configResult.error.message}", null, IssueLevel.ERROR)
//            PartialParsingResult.Complete(ModuleDirectoryParsingResult.Failure(listOf(error), listOf()))
//        }
//        is ModuleInfoParsingResult.Success -> {
//
//        }
//    }
//}

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
                val ir = dialect.parseToIR(file)
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
    val instance = definition.instantiate()

    // I think we could get typings on this if it were either generated from a spec or based on an annotation processor
    instance.setInput(CONFIG_TEXT, configFile.readText())

    for (file in directory.listFiles()) {
        instance.addKey(SOURCE_FILE_URLS, file.absolutePath)
        instance.setKeyedInput(SOURCE_TEXTS, file.absolutePath, file.readText())
    }

    instance.completeSynchronously()

    // TODO: Also handle the case where we get an error in config parsing
    val combinedParsingResult = instance.getNodeValue(COMBINED_PARSING_RESULT)
    when (combinedParsingResult) {
        is ParsingResult.Success -> {
            ModuleDirectoryParsingResult.Success(UnvalidatedModule(parsedConfig.info, combinedParsingResult.context))
        }
        is ParsingResult.Failure -> {
            ModuleDirectoryParsingResult.Failure(combinedParsingResult.errors, listOf())
        }
    }

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

internal val CONFIG_TEXT = NodeName<String>("configText")
internal val PARSED_CONFIG = NodeName<ModuleInfoParsingResult.Success>("parsedConfig")
internal val SOURCE_FILE_URLS = NodeName<String>("sourceFileUrls")
internal val SOURCE_TEXTS = KeyedNodeName<String, String>("sourceTexts")
internal val IRS = KeyedNodeName<String, Dialect.IR>("irs")
internal val TYPE_SUMMARIES = KeyedNodeName<String, TypesSummary>("typeSummaries")
internal val TYPE_INFO = NodeName<TypesInfo>("typeInfo")
internal val PARSING_RESULTS = KeyedNodeName<String, ParsingResult>("parsingResults")
internal val COMBINED_PARSING_RESULT = NodeName<ParsingResult>("combinedParsingResult")
internal fun getFilesParsingDefinition(): TrickleDefinition {
    val builder = TrickleDefinitionBuilder()
    val stringClass = kotlin.String::class.java
    val configTextInput = builder.createInputNode(CONFIG_TEXT)
    val parsedConfig = builder.createNode(PARSED_CONFIG, configTextInput, { text ->
        // Parse the config
        parseConfigFileString(text) as ModuleInfoParsingResult.Success
    })
    val sourceFileUrls = builder.createKeyListInputNode<String>(SOURCE_FILE_URLS)
    val sourceTexts = builder.createKeyedInputNode<String>(SOURCE_TEXTS, sourceFileUrls)

    val irs = builder.createKeyedNode(IRS, sourceFileUrls, sourceTexts.keyedOutput(), { filePath: String, sourceText: String ->
        // Do something, make the IR
        val dialect = determineDialect(filePath)!!
        dialect.parseToIR(sourceText)
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