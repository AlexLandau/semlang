package net.semlang.modules

import net.semlang.api.*
import net.semlang.api.parser.Issue
import net.semlang.api.parser.IssueLevel
import net.semlang.parser.*
import net.semlang.refill.*
import net.semlang.validator.*
import java.io.File

// TODO: Tweak this to handle the case where there is no module.conf (but how?)
val CONFIG_TEXT = NodeName<String>("configText")
val PARSED_CONFIG = NodeName<ModuleInfoParsingResult.Success>("parsedConfig")
val SOURCE_FILE_URLS = KeyListNodeName<String>("sourceFileUrls")
val SOURCE_TEXTS = KeyedNodeName<String, String>("sourceTexts")
val IRS = KeyedNodeName<String, Dialect.IR>("irs")
val TYPE_SUMMARIES = KeyedNodeName<String, TypesSummary>("typeSummaries")
val TYPES_INFO = NodeName<TypesInfo>("typesInfo")
val TYPES_METADATA = NodeName<TypesMetadata>("typesMetadata")
val PARSING_RESULTS = KeyedNodeName<String, ParsingResult>("parsingResults")
val COMBINED_PARSING_RESULT = NodeName<ParsingResult>("combinedParsingResult")
val MODULE_PARSING_RESULT = NodeName<ModuleDirectoryParsingResult>("moduleParsingResult")
val MODULE_VALIDATION_RESULT = NodeName<ValidationResult>("moduleValidationResult")
fun getFilesParsingDefinition(directory: File, repository: ModuleRepository): TrickleDefinition {
    val builder = TrickleDefinitionBuilder()
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

    val typesInfoNode = builder.createNode(TYPES_INFO, typeSummaries.fullOutput(), parsedConfig, { summaries, config ->
        val moduleName = config.info.name
        val upstreamModules = listOf<ValidatedModule>() // TODO: support
        // TODO: This can result in errors (when there are duplicate types); handle and report those errors
        val allTypesSummary = combineTypesSummaries(summaries)
        // TODO: Actually support upstream modules
        val moduleId = ModuleUniqueId(moduleName, "")
        val moduleVersionMappings = mapOf<ModuleNonUniqueId, ModuleUniqueId>()
        val recordIssue: (Issue) -> Unit = {} // TODO: Handle error case with conflicts across files
        getTypesInfoFromSummary(allTypesSummary, moduleId, upstreamModules, moduleVersionMappings, recordIssue)
    })
    // TODO: Combine with previous node
    val typesMetadataNode = builder.createNode(TYPES_METADATA, typesInfoNode, { typeInfo ->
        getTypesMetadata(typeInfo)
    })

    val parsingResultsNode = builder.createKeyedNode(PARSING_RESULTS, sourceFileUrls, irs.keyedOutput(), typesInfoNode, typesMetadataNode, { filePath, ir, typeInfo, typesMetadata ->
        try {
            val dialect = determineDialect(filePath)!!
            dialect.parseWithTypeInfo(ir, typeInfo, typesMetadata)
        } catch (e: RuntimeException) {
            e.printStackTrace()
            val message = "Parsing threw an exception: ${e.stackTrace.contentToString()}"
            val error = Issue(message, null, IssueLevel.ERROR)

            ParsingResult.Failure(listOf(error), RawContext(listOf(), listOf(), listOf()))
        }
    })

    val combinedParsingResultNode = builder.createNode(COMBINED_PARSING_RESULT, parsingResultsNode.fullOutput(), { parsingResults ->
        combineParsingResults(parsingResults)
    })

    val moduleParsingResultNode = builder.createNode(MODULE_PARSING_RESULT, parsedConfig, combinedParsingResultNode, { config, combinedParsingResult ->
        when (combinedParsingResult) {
            is ParsingResult.Success -> {
                ModuleDirectoryParsingResult.Success(UnvalidatedModule(config.info, combinedParsingResult.context))
            }
            is ParsingResult.Failure -> {
                ModuleDirectoryParsingResult.Failure(combinedParsingResult.errors, listOf())
            }
        }
    }, { error ->
        // TODO: We can do better than this (probably)
        System.err.println("Upstream errors: $error")
        for (e in error.errors.values) {
            e.printStackTrace()
        }
        ModuleDirectoryParsingResult.Failure(listOf(), listOf())
    })

    val moduleValidationResultNode = builder.createNode(MODULE_VALIDATION_RESULT, moduleParsingResultNode, typesInfoNode, typesMetadataNode, { parsingResult, typesInfo, typesMetadata ->
        when (parsingResult) {
            is ModuleDirectoryParsingResult.Success -> {
                val module = parsingResult.module

                val dependencies = module.info.dependencies.map { dependencyId ->
                    val uniqueId = repository.getModuleUniqueId(dependencyId, directory)
                    repository.loadModule(uniqueId)
                }

                // TODO: Pass in types info and metadata as well
                validateModule(module.contents, typesInfo, typesMetadata, module.info.name, CURRENT_NATIVE_MODULE_VERSION, dependencies)
            }
            is ModuleDirectoryParsingResult.Failure -> {
                ValidationResult.Failure(parsingResult.errors, parsingResult.warnings)
            }
        }
    })

    return builder.build()
}
