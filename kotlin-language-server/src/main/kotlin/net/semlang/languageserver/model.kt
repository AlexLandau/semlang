package net.semlang.languageserver

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleId
import net.semlang.modules.ModuleInfo
import net.semlang.modules.ModuleInfoParsingResult
import net.semlang.modules.parseConfigFileString
import net.semlang.parser.*
import org.eclipse.lsp4j.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.concurrent.LinkedBlockingQueue

// TODO: When client-side file updates work, do all file management/watching through the client. Until then, we'll need
// something more manual.
class AllModulesModel(private val languageClientProvider: LanguageClientProvider) {
    // TODO: Clean up after removing models (requires shutting down worker threads)
    private val foldersToModelsMap = HashMap<URI, SourcesFolderModel>()

    fun documentWasOpened(uri: String, text: String) {
        // TODO: Make this less silly
        System.err.println("Handling documentWasOpened for document with URI $uri")
        val uriObject = URI(uri)
        System.err.println("URI object is: $uriObject")
        val containingFolder = uriObject.resolve(".")
        val resolved = uriObject.resolve("module.conf")
        System.err.println("Resolved URI is: $resolved")

        val existingModel = foldersToModelsMap[containingFolder]
        if (existingModel != null) {
            existingModel.openDocument(uri, text)
        } else {
            val newModel = SourcesFolderModel(containingFolder, languageClientProvider)
            foldersToModelsMap.put(containingFolder, newModel)
            newModel.openDocument(uri, text)
        }
    }

    fun documentWasUpdated(uri: String, text: String) {
        val uriObject = URI(uri)
        val containingFolder = uriObject.resolve(".")

        val existingModel = foldersToModelsMap[containingFolder]
        if (existingModel != null) {
            existingModel.updateDocument(uri, text)
        } else {
            error("Got document update for unknown document $uri")
        }
    }

    fun documentWasClosed(uri: String) {
        val uriObject = URI(uri)
        val containingFolder = uriObject.resolve(".")

        // TODO: We'll want to do the closing within the model, vs. closing the model itself
        val existingModel = foldersToModelsMap[containingFolder]
        if (existingModel != null) {
            existingModel.closeDocument(uri)
        }
    }
}

// This represents all the information we gather at a given time about the state of the file system, as well as of the
// state of files the client is telling us about.
// Note: All files are referred to by their name only. Only includes files named "*.sem" or "module.conf".
data class SourcesFolderState(
        /** Files opened by the client. Map is from name to the text of the file. */
        val openFiles: Map<String, String>,
        /** Files not opened by the client, which instead are read/watched by the server directly. */
        val otherFiles: Map<String, FileOnDiskState>
) {
    init {
        for (fileName in openFiles.keys) {
            if (otherFiles.containsKey(fileName)) {
                error("Consistency error in SourcesFolderState: File name $fileName is included as both an open file and a non-open file")
            }
        }
    }
}
data class FileOnDiskState(val text: String, val lastModifiedTime: FileTime)

sealed class DocumentSource {
    data class Client(val text: String): DocumentSource()
    data class FileSystem(val lastModifiedTime: FileTime): DocumentSource()
}

/*
 * This will contain our notion of what the current source files are for a given module and manage accepting updates
 * and triggering rebuilds that produce diagnostics.
 *
 * Note that this only handles a single module.
 */
// TODO: We'll also need to load module specification files and be able to load files off disk based on those
// TODO: Actually have a format for module specification files...
class SourcesFolderModel(private val folderUri: URI,
                         private val languageClientProvider: LanguageClientProvider) {
    @Volatile private var folderState = SourcesFolderState(mapOf(), mapOf())

    private val sourcesUsedForParsingResults = HashMap<String, DocumentSource>()
    private val parsingResultsByDocumentName = HashMap<String, ParsingResult>()

    @Volatile private var moduleInfo: ModuleInfo? = null // Note: Will be null if there is no valid module.conf

    private val workQueue = LinkedBlockingQueue<() -> Unit>()

    private fun getSourcesNeedingReparsing(): Set<String> {
        val folderState = this.folderState
        val sourcesNeedingReparsing = HashSet<String>()

        System.err.println("Checking sources needing reparsing in the model for folder $folderUri...")

        folderState.openFiles.forEach { openFileName, openFileText ->
            val curSource = sourcesUsedForParsingResults[openFileName]
            if (curSource == null || curSource !is DocumentSource.Client || curSource.text != openFileText) {
                System.err.println("$openFileName needs reparsing based on open files")
                sourcesNeedingReparsing.add(openFileName)
            }
        }

        folderState.otherFiles.forEach { fileName, fileState ->
            val curSource = sourcesUsedForParsingResults[fileName]
            if (curSource == null || curSource !is DocumentSource.FileSystem || curSource.lastModifiedTime != fileState.lastModifiedTime) {
                System.err.println("$fileName needs reparsing based on closed files: ${(curSource as? DocumentSource.FileSystem)?.lastModifiedTime} vs. ${fileState.lastModifiedTime}")
                sourcesNeedingReparsing.add(fileName)
            }
        }

        sourcesUsedForParsingResults.keys.forEach { fileName ->
            if (!folderState.openFiles.containsKey(fileName) && !folderState.otherFiles.containsKey(fileName)) {
                System.err.println("$fileName needs reparsing based on lack of any known files")
                sourcesNeedingReparsing.add(fileName)
            }
        }
        System.err.println("Sources needing reparsing: $sourcesNeedingReparsing")
        return sourcesNeedingReparsing
    }

    /*
     * Note: One of the main reasons we have this kind of dedicated thread is so all the handling of the model's internal
     * state is serialized and therefore easier to reason about. Another approach would be to use a shared thread pool
     * among models, and use a monitor lock on this model for synchronization.
     */
    private val workerThread = Thread(fun() {
        while (true) {
            val workItem = workQueue.take()
            try {
                workItem()
            } catch(e: Exception) {
                e.printStackTrace(System.err)
            }

            // Wait until there are no more external changes to files coming in, then figure out which tasks to add to
            // update the model
            if (workQueue.isEmpty()) {
                // TODO: Figure out needed tasks to update the model, based on the current folderState
                addNeededModelUpdateTasks()
            }
        }
    })

    private fun addNeededModelUpdateTasks() {
        // TODO: Make this more fine-grained at some point in the future
        val sourcesNeedingReparsing = getSourcesNeedingReparsing()
        if (sourcesNeedingReparsing.isNotEmpty()) {
            workQueue.add(getReparseAndRevalidateTask(sourcesNeedingReparsing))
        }
    }

    private fun getReparseAndRevalidateTask(sourcesNeedingReparsing: Set<String>): () -> Unit {
        return fun() {
            val folderState = this.folderState
            sourcesNeedingReparsing.forEach { fileName ->
                val openFileText = folderState.openFiles[fileName]
                // Null if the file doesn't exist (can't be accessed, etc.)
                val fileText: String? = if (openFileText != null) {
                    sourcesUsedForParsingResults[fileName] = DocumentSource.Client(openFileText)
                    openFileText
                } else {
                    val otherFileState = folderState.otherFiles[fileName]
                    if (otherFileState != null) {
                        sourcesUsedForParsingResults[fileName] = DocumentSource.FileSystem(otherFileState.lastModifiedTime)
                        otherFileState.text
                    } else {
                        sourcesUsedForParsingResults.remove(fileName)
                        null
                    }
                }

                if (fileName == "module.conf") {
                    if (fileText != null) {
                        // TODO: Handle invalid configs
                        val configParsingResult = parseConfigFileString(fileText)
                        if (configParsingResult is ModuleInfoParsingResult.Success) {
                            this.moduleInfo = configParsingResult.info
                        } else {
                            if (configParsingResult is ModuleInfoParsingResult.Failure) {
                                System.err.println("Config parsing failed: ${configParsingResult.error}")
                            }
                            this.moduleInfo = null
                        }
                    } else {
                        this.moduleInfo = null
                    }
                } else {
                    if (fileText != null) {
                        val parsingResult = parseString(fileText, getDocumentUriForFileName(fileName))
                        parsingResultsByDocumentName[fileName] = parsingResult
                    } else {
                        parsingResultsByDocumentName.remove(fileName)
                    }
                }
            }

            val moduleInfo = this.moduleInfo
            if (moduleInfo == null) {
                // Treat each file as its own module
                parsingResultsByDocumentName.forEach { fileName, parsingResult ->
                    val fakeModuleId = ModuleId("non-module", fileName.split(".")[0], "dev")
                    val validationResult = validate(parsingResult, fakeModuleId, CURRENT_NATIVE_MODULE_VERSION)

                    val diagnostics = collectDiagnostics(validationResult.getAllIssues(), listOf(getDocumentUriForFileName(fileName)))

                    val client = languageClientProvider.getLanguageClient()
                    diagnostics.forEach { uri, diagnosticsList ->
                        // TODO: Maintain a list of the last set of diagnostics per document, avoid re-sending
                        // if nothing has changed
                        val diagnosticsParams = PublishDiagnosticsParams(uri, diagnosticsList)
                        client.publishDiagnostics(diagnosticsParams)
                    }
                }
            } else {
                // We have a single module with a valid config
                val combinedParsingResult = combineParsingResults(parsingResultsByDocumentName.values)
                val validationResult = validate(combinedParsingResult, moduleInfo.id, CURRENT_NATIVE_MODULE_VERSION)

                val documentUris = (parsingResultsByDocumentName.keys).toList().map(this::getDocumentUriForFileName)
                val diagnostics = collectDiagnostics(validationResult.getAllIssues(), documentUris)

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

    private fun getDocumentUriForFileName(fileName: String): String {
        val documentUri = folderUri.resolve(fileName)
        return documentUri.toASCIIString()
    }

    init {
        // TODO: Shut this down at some point...
        workerThread.start()
        // TODO: Add periodic scanning (watching) of the folder
        workQueue.add(getScanFolderTask())
    }

    private fun getScanFolderTask(): () -> Unit {
        return fun() {
            if (!folderUri.scheme.equals("file", true)) {
                error("Non-file path: $folderUri")
            }
            val folderPath = Paths.get(folderUri)
            System.err.println("Folder path we're checking: $folderPath")
            if (!Files.isDirectory(folderPath)) {
                System.err.println("Looks like $folderPath isn't a directory")
                this.folderState = SourcesFolderState(mapOf(), mapOf())
                return
            }
            val openFilesState = this.folderState.openFiles
            val oldOtherFilesState = this.folderState.otherFiles
            val newOtherFilesState = HashMap<String, FileOnDiskState>()
            Files.list(folderPath).forEach { filePath ->
                val fileName = filePath.toFile().name
                if (isRelevantFilename(fileName)) {
                    // Don't load it if it's open in the client
                    if (!openFilesState.containsKey(fileName)) {
                        // Is it newer than the existing version we know about?
                        val lastModifiedTime: FileTime = getLastModifiedTime(filePath)

                        val oldFileState = oldOtherFilesState[fileName]
                        if (oldFileState == null || oldFileState.lastModifiedTime < lastModifiedTime) {
                            val loadedText = filePath.toFile().readText()
                            newOtherFilesState[fileName] = FileOnDiskState(loadedText, lastModifiedTime)
                        } else {
                            newOtherFilesState[fileName] = oldFileState
                        }
                    }
                }
            }
            this.folderState = SourcesFolderState(openFilesState, newOtherFilesState)
        }
    }

    private fun isRelevantFilename(fileName: String): Boolean {
        return fileName == "module.conf" || fileName.endsWith(".sem")
    }

    fun openDocument(uri: String, text: String) {
        workQueue.add(getOpenDocumentTask(uri, text))
    }

    private fun getOpenDocumentTask(uri: String, text: String): () -> Unit {
        return fun() {
            val oldOpenFilesState = this.folderState.openFiles
            val oldOtherFilesState = this.folderState.otherFiles
            val newOpenFilesState = HashMap<String, String>(oldOpenFilesState)
            val newOtherFilesState = HashMap<String, FileOnDiskState>(oldOtherFilesState)

            // TODO: It's probably better to convert to URI objects at an earlier level
            val filePath = Paths.get(URI(uri))
            val fileName = filePath.toFile().name

            newOtherFilesState.remove(fileName)
            newOpenFilesState.put(fileName, text)

            this.folderState = SourcesFolderState(newOpenFilesState, newOtherFilesState)
        }
    }

    fun updateDocument(uri: String, text: String) {
        workQueue.add(getUpdateDocumentTask(uri, text))
    }

    private fun getUpdateDocumentTask(uri: String, text: String): () -> Unit {
        return fun() {
            val oldOpenFilesState = this.folderState.openFiles
            val newOpenFilesState = HashMap<String, String>(oldOpenFilesState)
            val otherFilesState = this.folderState.otherFiles

            val filePath = Paths.get(URI(uri))
            val fileName = filePath.toFile().name

            newOpenFilesState.put(fileName, text)

            this.folderState = SourcesFolderState(newOpenFilesState, otherFilesState)
        }
    }

    fun closeDocument(uri: String) {
        workQueue.add(getCloseDocumentTask(uri))
    }

    private fun getCloseDocumentTask(uri: String): () -> Unit {
        return fun() {
            val oldOpenFilesState = this.folderState.openFiles
            val oldOtherFilesState = this.folderState.otherFiles
            val newOpenFilesState = HashMap<String, String>(oldOpenFilesState)
            val newOtherFilesState = HashMap<String, FileOnDiskState>(oldOtherFilesState)

            val filePath = Paths.get(URI(uri))
            val fileName = filePath.toFile().name

            newOpenFilesState.remove(fileName)
            if (isRelevantFilename(fileName) && Files.isRegularFile(filePath)) {
                val lastModifiedTime = getLastModifiedTime(filePath)
                val fileText = filePath.toFile().readText()
                val fileState = FileOnDiskState(fileText, lastModifiedTime)
                newOtherFilesState.put(fileName, fileState)
            }

            this.folderState = SourcesFolderState(newOpenFilesState, newOtherFilesState)
        }
    }

    private fun collectDiagnostics(issues: List<Issue>, documentUris: List<String>): Map<String, List<Diagnostic>> {
        val diagnosticsByUri = HashMap<String, ArrayList<Diagnostic>>()
        for (documentUri in documentUris) {
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

private fun getLastModifiedTime(filePath: Path): FileTime {
    val lastModifiedTime: FileTime = Files.getAttribute(filePath, "lastModifiedTime") as FileTime
    return lastModifiedTime
}
