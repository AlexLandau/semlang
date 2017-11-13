package net.semlang.languageserver

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.parser.*
import org.eclipse.lsp4j.*
import java.util.concurrent.LinkedBlockingQueue

class AllModulesModel(private val languageClientProvider: LanguageClientProvider) {
    // TODO: Clean up after removing models (requires shutting down worker threads)
    private val documentsToModelsMap = HashMap<String, ModuleSourcesModel>()

    fun documentWasOpened(uri: String, text: String) {
        // TODO: Make this less silly
        val existingModel = documentsToModelsMap[uri]
        if (existingModel != null) {
            existingModel.updateDocument(uri, text)
        } else {
            val newModel = ModuleSourcesModel(languageClientProvider, ModuleId("foo", "debug", "0.0.1"), CURRENT_NATIVE_MODULE_VERSION)
            documentsToModelsMap.put(uri, newModel)
            newModel.updateDocument(uri, text)
        }
    }

    fun documentWasUpdated(uri: String, text: String) {
        val existingModel = documentsToModelsMap[uri]
        if (existingModel != null) {
            existingModel.updateDocument(uri, text)
        } else {
            error("Got document update for unknown document $uri")
        }
    }
}

/*
 * This will contain our notion of what the current source files are for a given module and manage accepting updates
 * and triggering rebuilds that produce diagnostics.
 *
 * Note that this only handles a single module.
 */
class ModuleSourcesModel(private val languageClientProvider: LanguageClientProvider,
                         private val moduleId: ModuleId,
                         private val nativeModuleVersion: String) {
    private val documentTextsByUri = HashMap<String, String>()
    private val parsingResultsByDocumentUri = HashMap<String, ParsingResult>()
    @Volatile private var combinedParsingResult: ParsingResult? = null
    @Volatile private var validationResult: ValidationResult? = null
    private val workQueue = LinkedBlockingQueue<() -> Unit>()

    private val workerThread = Thread(fun() {
        while (true) {
            val workItem = workQueue.take()
            workItem()
        }
    })

    init {
        // TODO: Shut this down at some point...
        workerThread.start()
    }

    fun updateDocument(uri: String, text: String) {
        workQueue.add(fun() {
            if (documentTextsByUri[uri] != text) {
                documentTextsByUri.put(uri, text)
                workQueue.add(fun() {
                    val parsingResult = parseString(text, uri)
                    parsingResultsByDocumentUri[uri] = parsingResult

                    workQueue.add(getRecombineTask())
                })
            }
        })
    }

    private fun getRecombineTask(): () -> Unit {
        return fun() {
            val oldResult = combinedParsingResult
            combinedParsingResult = combineParsingResults(parsingResultsByDocumentUri.values)

            if (oldResult != combinedParsingResult) {
                validationResult = validate(combinedParsingResult!!, moduleId, nativeModuleVersion)
                // TODO: Report diagnostics
                workQueue.add(getReportDiagnosticsTask())
            }
        }
    }

    private fun getReportDiagnosticsTask(): () -> Unit {
        return fun() {
            val result = validationResult
            if (result != null) {
                val diagnostics = collectDiagnostics(result.getAllIssues())

                val client = languageClientProvider.getLanguageClient()
                diagnostics.forEach { uri, diagnosticsList ->
                    // TODO: Maintain a list of the last set of diagnostics per document, avoid re-sending
                    // if nothing has changed
                    val diagnosticsParams = PublishDiagnosticsParams(uri, diagnosticsList)
                    client.publishDiagnostics(diagnosticsParams)
                }
            }
        }
    }

    private fun collectDiagnostics(issues: List<Issue>): Map<String, List<Diagnostic>> {
        val diagnosticsByUri = HashMap<String, ArrayList<Diagnostic>>()
        for (documentUri in documentTextsByUri.keys) {
            diagnosticsByUri.put(documentUri, ArrayList<Diagnostic>())
        }
        for (issue in issues) {
            val range = toLsp4jRange(issue.location)
            val severity = toLsp4jSeverity(issue.level)
            val diagnostic = Diagnostic(range, issue.message, severity, "semlang validator")
            // TODO: Temporary workaround...
            val documentUri = issue.location!!.documentUri
            System.err.println("documentUri: " + documentUri)
            diagnosticsByUri[documentUri]!!.add(diagnostic)
        }
        return diagnosticsByUri
    }

    private fun toLsp4jSeverity(level: IssueLevel): DiagnosticSeverity {
        return when (level) {
            IssueLevel.WARNING -> DiagnosticSeverity.Warning
            IssueLevel.ERROR -> DiagnosticSeverity.Error
        }
    }

    private fun toLsp4jRange(location: net.semlang.api.Location?): Range {
        if (location == null) {
            return Range(Position(0, 0), Position(0, 1))
        } else {
            return Range(toLsp4jPosition(location.range.start), toLsp4jPosition(location.range.end))
        }
    }

    private fun toLsp4jPosition(position: net.semlang.api.Position): Position {
        return Position(position.lineNumber - 1, position.column)
    }
}
