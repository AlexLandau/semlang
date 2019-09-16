package net.semlang.modules

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleNonUniqueId
import net.semlang.api.ModuleUniqueId
import net.semlang.api.ValidatedModule
import net.semlang.api.parser.Issue
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
val TYPE_INFO = NodeName<TypesInfo>("typeInfo")
val PARSING_RESULTS = KeyedNodeName<String, ParsingResult>("parsingResults")
val COMBINED_PARSING_RESULT = NodeName<ParsingResult>("combinedParsingResult")
val MODULE_PARSING_RESULT = NodeName<ModuleDirectoryParsingResult>("moduleParsingResult")
val MODULE_VALIDATION_RESULT = NodeName<ValidationResult>("moduleValidationResult")
fun getFilesParsingDefinition(directory: File, repository: ModuleRepository): TrickleDefinition {
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
        System.err.println("Regenerating IR for $filePath")
        val dialect = determineDialect(filePath)!!
        dialect.parseToIR(filePath, sourceText)
    })
    val typeSummaries = builder.createKeyedNode(TYPE_SUMMARIES, sourceFileUrls, irs.keyedOutput(), parsedConfig, { filePath, ir, config ->
        // Do something, make the type summary
        System.err.println("Regenerating type summary for $filePath")
        val dialect = determineDialect(filePath)!!
        val moduleName = config.info.name
        val upstreamModules = listOf<ValidatedModule>() // TODO: support
        dialect.getTypesSummary(ir, moduleName, upstreamModules)
    })

    val typeInfoNode = builder.createNode(TYPE_INFO, typeSummaries.fullOutput(), parsedConfig, { summaries, config ->
        System.err.println("Regenerating all type info")
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
        System.err.println("Regenerating parsing result for $filePath")
        val dialect = determineDialect(filePath)!!
        dialect.parseWithTypeInfo(ir, typeInfo)
    })

    val combinedParsingResultNode = builder.createNode(COMBINED_PARSING_RESULT, parsingResultsNode.fullOutput(), { parsingResults ->
        System.err.println("Regenerating combined parsing results")
        combineParsingResults(parsingResults)
    })

    val moduleParsingResultNode = builder.createNode(MODULE_PARSING_RESULT, parsedConfig, combinedParsingResultNode, { config, combinedParsingResult ->
        System.err.println("Regenerating module parsing result")
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

    val moduleValidationResultNode = builder.createNode(MODULE_VALIDATION_RESULT, moduleParsingResultNode, { parsingResult ->
        System.err.println("Regenerating module validation result")
        when (parsingResult) {
            is ModuleDirectoryParsingResult.Success -> {
                val module = parsingResult.module

                val dependencies = module.info.dependencies.map { dependencyId ->
                    val uniqueId = repository.getModuleUniqueId(dependencyId, directory)
                    repository.loadModule(uniqueId)
                }

                validateModule(module.contents, module.info.name, CURRENT_NATIVE_MODULE_VERSION, dependencies)
            }
            is ModuleDirectoryParsingResult.Failure -> {
                ValidationResult.Failure(parsingResult.errors, parsingResult.warnings)
            }
        }
    })

    return builder.build()
}
