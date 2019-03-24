package net.semlang.modules

import net.semlang.api.Location
import net.semlang.api.Position
import net.semlang.api.Range
import net.semlang.parser.*
import net.semlang.sem2.translate.collectTypeInfo
import net.semlang.sem2.translate.translateSem2ContextToSem1
import net.semlang.validator.*
import java.io.File
import java.lang.UnsupportedOperationException

sealed class ModuleDirectoryParsingResult {
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

        override fun getTypesSummary(ir: IR): TypesInfo {
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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

        override fun getTypesSummary(ir: IR): TypesInfo {
            collectTypeInfo(context, moduleName, upstreamModules)
            TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
                        // TODO: It would be reasonable for the sem1 and sem2 parsers (and other dialects) to share APIs for location and issues
                        sem2Result.errors.map { Issue(it.message, translate(it.location), translate(it.level)) },
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
    abstract fun getTypesSummary(ir: IR): TypesInfo
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
            // TODO: Generalize to support "external" dialects
            val allFiles = directory.listFiles()
            val filesByDialect = sortByDialect(allFiles)

            val combinedParsingResult = if (filesByDialect.keys.any { it.needsTypeInfoToParse }) {
                // Collect type info in one pass, then parse in a second pass
                collectParsingResultsTwoPasses(filesByDialect)
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

//            val allTypeInfo = collectAllTypeInfo(filesByDialect)


//            val sem2Files = directory.listFiles { dir, name -> name.endsWith(".sem2") }
//            val moduleName = parsedConfig.info.name
//            val upstreamModules = listOf<ValidatedModule>() // TODO: Support dependencies
//            val sem2ParsingResults = sem2Files.map { file ->
//                try {
//                    val parsed = net.semlang.sem2.parser.parseFile(file)
//                    when (parsed) {
//                        is net.semlang.sem2.parser.ParsingResult.Success -> {
//                            val sem1Context = translateSem2ContextToSem1(parsed.context, moduleName, upstreamModules)
//                            ParsingResult.Success(sem1Context)
//                        }
//                        is net.semlang.sem2.parser.ParsingResult.Failure -> {
//                            val sem1PartialContext = translateSem2ContextToSem1(parsed.partialContext, moduleName, upstreamModules)
//                            ParsingResult.Failure(
//                                    // TODO: It would be reasonable for the sem1 and sem2 parsers (and other dialects) to share APIs for location and issues
//                                    parsed.errors.map { Issue(it.message, translate(it.location), translate(it.level)) },
//                                    sem1PartialContext
//                            )
//                        }
//                    }
//                } catch (e: RuntimeException) {
//                    throw RuntimeException("Error parsing file $file", e)
//                }
//
//            }


        }
    }

}

fun collectParsingResultsTwoPasses(filesByDialect: Map<Dialect, List<File>>): ParsingResult {
    val typesSummaries = ArrayList<TypesInfo>()
    val irs = HashMap<File, Dialect.IR>()
    for ((dialect, files) in filesByDialect.entries) {
        for (file in files) {
            try {
                val ir = dialect.parseToIR(file)
                irs[file] = ir
                typesSummaries.add(dialect.getTypesSummary(ir))
            } catch (e: RuntimeException) {
                throw RuntimeException("Error collecting types summary from file $file", e)
            }
        }
    }

    val allTypesSummary = combineTypesSummaries(typesSummaries)

    val parsingResults = ArrayList<ParsingResult>()
    for ((dialect, files) in filesByDialect.entries) {
        for (file in files) {
            try {
                parsingResults.add(dialect.parseWithTypeInfo(irs[file]!!, allTypesSummary))
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

fun translate(location: net.semlang.sem2.api.Location?): Location? {
    if (location == null) {
        return null
    }
    return Location(location.documentUri, translate(location.range))
}

fun translate(range: net.semlang.sem2.api.Range): Range {
    return Range(translate(range.start), translate(range.end))
}

fun translate(pos: net.semlang.sem2.api.Position): Position {
    return Position(pos.lineNumber, pos.column, pos.rawIndex)
}

fun translate(level: net.semlang.sem2.parser.IssueLevel): IssueLevel {
    return when (level) {
        net.semlang.sem2.parser.IssueLevel.WARNING -> IssueLevel.WARNING
        net.semlang.sem2.parser.IssueLevel.ERROR -> IssueLevel.ERROR
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
