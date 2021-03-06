package net.semlang.languageserver

import net.semlang.api.CURRENT_NATIVE_MODULE_VERSION
import net.semlang.api.ModuleName
import net.semlang.api.parser.Issue
import net.semlang.api.parser.IssueLevel
import net.semlang.modules.*
import net.semlang.parser.ModuleInfoParsingResult
import net.semlang.parser.parseConfigFileString
import net.semlang.parser.*
import net.semlang.refill.*
import net.semlang.validator.parseAndValidateString
import net.semlang.validator.validate
import org.eclipse.lsp4j.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.concurrent.GuardedBy
import kotlin.system.exitProcess

// TODO: When client-side file updates work, do all file management/watching through the client. Until then, we'll need
// something more manual.
class AllModulesModel(private val languageClientProvider: LanguageClientProvider) {
    // TODO: Clean up after removing models (requires shutting down worker threads)
    private val foldersToModelsMap = HashMap<URI, SourcesFolderModel>()

    fun documentWasOpened(uri: String, text: String) {
        System.err.println("Handling documentWasOpened for document with URI $uri")
        val uriObject = URI(uri)
        System.err.println("uriObject output: ${uriObject.toASCIIString()}")
        val containingFolder = uriObject.resolve(".")
        System.err.println("containingFolder output: ${containingFolder.toASCIIString()}")

        val existingModel = foldersToModelsMap[containingFolder]
        if (existingModel != null) {
            existingModel.openDocument(uri, text)
        } else {
            val newModel = ModuleOrFilesFolderModel(containingFolder, languageClientProvider)
            foldersToModelsMap.put(containingFolder, newModel)
            newModel.openDocument(uri, text)
        }
    }

    fun documentWasUpdated(uri: String, text: String) {
        System.err.println("Handling documentWasUpdated for document with URI $uri")
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

interface SourcesFolderModel {
    fun openDocument(uri: String, text: String)
    fun updateDocument(uri: String, text: String)
    fun closeDocument(uri: String)
}

// TODO: This has a bug where when opening a module folder (i.e. just one source file is open), it's first treated as
// individual files (and results for that are shown) before it's converted to a module-based model; we should delay
// until the first file scan happens
class ModuleOrFilesFolderModel(private val folderUri: URI,
                               private val languageClientProvider: LanguageClientProvider): SourcesFolderModel {
    // TODO: Not sure about the choice of executor here...
    private val executor = Executors.newFixedThreadPool(4)

    // TODO: I think I'm using volatile wrong here
    @Volatile var folderState = SourcesFolderState(mapOf(), mapOf())
    @GuardedBy("this")
    var curDelegate: InternalFolderModel? = null

    init {
        // Scan the folder first, to make sure we don't use the wrong model if openDocument() is called right away...
        getScanFolderRunnable()()
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(fun() {
            executor.submit(getScanFolderRunnable())
        }, 10, 10, TimeUnit.SECONDS)
    }

//    private fun getDocumentUriForFileName(fileName: String): String {
//        val documentUri = folderUri.resolve(fileName)
//        return documentUri.toASCIIString()
//    }

    @Synchronized
    private fun switchDelegateIfNeeded() {
        if (folderState.openFiles.containsKey("module.conf") || folderState.otherFiles.containsKey("module.conf")) {
            if (curDelegate !is ModuleFolderModel) {
                curDelegate = ModuleFolderModel(folderUri, languageClientProvider, executor)
                for ((name, text) in folderState.openFiles) {
                    val uri = getDocumentUriForFileName(name)
                    curDelegate!!.setText(uri, text)
                }
                for ((name, state) in folderState.otherFiles) {
                    val uri = getDocumentUriForFileName(name)
                    curDelegate!!.setText(uri, state.text)
                }
            }
        } else {
            if (curDelegate !is IndividualFilesFolderModel) {
                curDelegate = IndividualFilesFolderModel(folderUri, languageClientProvider, executor)
                for ((name, text) in folderState.openFiles) {
                    val uri = getDocumentUriForFileName(name)
                    curDelegate!!.setText(uri, text)
                }
                for ((name, state) in folderState.otherFiles) {
                    val uri = getDocumentUriForFileName(name)
                    curDelegate!!.setText(uri, state.text)
                }
            }
        }
    }

    private fun getNameFromUri(uri: String): String {
        return Paths.get(URI(uri)).toFile().name
    }

    private fun getDocumentUriForFileName(fileName: String): String {
        val documentUri = folderUri.resolve(fileName)
        val string = documentUri.toASCIIString()
        if (string.startsWith("file:/") && !string.startsWith("file:///")) {
            return string.replaceFirst("file:/", "file:///")
        }
        return string
    }

    @Synchronized
    override fun openDocument(uri: String, text: String) {
        if (uri != getDocumentUriForFileName(getNameFromUri(uri))) {
            System.err.println("URI $uri becomes ${getDocumentUriForFileName(getNameFromUri(uri))} after a round trip; this may cause problems, such as treating the file as two files")
        }
        val name = getNameFromUri(uri)
        folderState = folderState.copy(openFiles = folderState.openFiles + mapOf(name to text), otherFiles = folderState.otherFiles - name)
        switchDelegateIfNeeded()
        curDelegate?.setText(uri, text)
    }

    @Synchronized
    override fun updateDocument(uri: String, text: String) {
        val name = getNameFromUri(uri)
        folderState = folderState.copy(openFiles = folderState.openFiles + mapOf(name to text))
        switchDelegateIfNeeded()
        curDelegate?.setText(uri, text)
    }

    @Synchronized
    override fun closeDocument(uri: String) {
        val filePath = Paths.get(URI(uri))
        val name = filePath.toFile().name

        val newOtherFilesState = folderState.otherFiles.toMutableMap()
        if (isRelevantFilename(name) && Files.isRegularFile(filePath)) {
            val lastModifiedTime = getLastModifiedTime(filePath)
            val fileText = filePath.toFile().readText()
            val fileState = FileOnDiskState(fileText, lastModifiedTime)
            newOtherFilesState.put(name, fileState)
        }

        folderState = folderState.copy(openFiles = folderState.openFiles - name, otherFiles = newOtherFilesState)

        switchDelegateIfNeeded()
        if (folderState.otherFiles.containsKey(name)) {
            curDelegate?.setText(uri, folderState.otherFiles[name]!!.text)
        } else {
            curDelegate?.closeDocument(uri)
        }
    }

    private fun getScanFolderRunnable(): () -> Unit {
        return fun() {
            try {
                if (!folderUri.scheme.equals("file", true)) {
                    error("Non-file path: $folderUri")
                }
                val folderPath = Paths.get(folderUri)
//                System.err.println("Folder path we're checking: $folderPath")
                if (!Files.isDirectory(folderPath)) {
                    System.err.println("Looks like $folderPath isn't a directory")
                    this.folderState = SourcesFolderState(mapOf(), mapOf())
                    return
                }
                // TODO: The synchronization story here is pretty coarse-grained
                synchronized(this) {
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
                                    val uri = getDocumentUriForFileName(fileName)
                                    System.err.println("The inferred document URI for $fileName is $uri (line 233)")
                                    curDelegate?.setText(uri, loadedText)
                                } else {
                                    newOtherFilesState[fileName] = oldFileState
                                }
                            }
                        }
                    }
                    this.folderState = SourcesFolderState(openFilesState, newOtherFilesState)
                    switchDelegateIfNeeded()
                }
            } catch (e: Exception) {
                // This just gets swallowed otherwise...
                e.printStackTrace()
            }
        }
    }
}

interface InternalFolderModel {
    fun setText(uri: String, text: String)
    fun closeDocument(uri: String)
}

// TODO: Additional things this needs to do:
// 1. Scan the folder periodically to find new files (previous one did it every 10 seconds) (done)
// 2. Manage the module.conf (done)
// 3. When we get the parsing results, send them to the language client (done, maybe)
// TODO: How is this going to scan the folder periodically?
// TODO: We need to track which files we are getting from the language client vs. which we get from
class ModuleFolderModel(private val folderUri: URI,
                        private val languageClientProvider: LanguageClientProvider,
                        private val executor: ExecutorService): InternalFolderModel {
    private val instance = getFilesParsingDefinition(Paths.get(folderUri).toFile(), getDefaultLocalRepository()).instantiateAsync(executor)

    init {
        instance.addBasicListener(MODULE_VALIDATION_RESULT, TrickleEventListener { event ->
            if (event is TrickleEvent.Computed) {
                try {
                    val validationResult = event.value

                    val allIssues = validationResult.getAllIssues()
                    // TODO: This could be made slightly more precise by connecting this to the output in the Refill logic
                    val filenames = instance.getOutcome(SOURCE_FILE_URLS) // Note: This does actually contain names, not URLs
                    val documentUris = (filenames as NodeOutcome.Computed<List<String>>).value.map(this::getDocumentUriForFileName)
                    val diagnostics = collectDiagnostics(allIssues, documentUris)

                    val client = languageClientProvider.getLanguageClient()
                    diagnostics.forEach { uri, diagnosticsList ->
                        // TODO: Maintain a list of the last set of diagnostics per document, avoid re-sending
                        // if nothing has changed
                        val diagnosticsParams = PublishDiagnosticsParams(uri, diagnosticsList)
                        client.publishDiagnostics(diagnosticsParams)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                System.err.println("(debug) Trickle event: $event")
                if (event is TrickleEvent.Failure) {
                    for ((valueId, throwable) in event.failure.errors) {
                        System.err.println("For $valueId:")
                        throwable.printStackTrace()
                    }
                }
                /*
                (debug) Trickle event: Failure(valueId=Nonkeyed(nodeName=moduleValidationResul t), failure=TrickleFailure(errors={Nonkeyed(nodeName=moduleValidationResul t)=java.lang.IllegalStateException: Validation error, position not recorded: In function Integer.toString, resolved a function with ID Nat but could not find the signature}, missingInputs=[]), timestamp=51)
                 */
            }
        })
    }

    private fun getDocumentUriForFileName(fileName: String): String {
        val documentUri = folderUri.resolve(fileName)
        val string = documentUri.toASCIIString()
        if (string.startsWith("file:/") && !string.startsWith("file:///")) {
            return string.replaceFirst("file:/", "file:///")
        }
        return string
    }

    @Synchronized
    override fun setText(uri: String, text: String) {
        System.err.println("Running ModuleFolderModel.setText")
        // TODO: Should we check that a separator comes before this? (Here and elsewhere)
        if (uri.endsWith("module.conf")) {
            instance.setInput(CONFIG_TEXT, text)
        } else {
            instance.setInputs(
                listOf(
                    TrickleInputChange.EditKeys(SOURCE_FILE_URLS, listOf(uri), listOf()),
                    TrickleInputChange.SetKeyed(SOURCE_TEXTS, mapOf(uri to text))
                )
            )
        }
    }

    @Synchronized
    override fun closeDocument(uri: String) {
        System.err.println("Running ModuleFolderModel.closeDocument")
        if (uri.endsWith("module.conf")) {
            error("If the module.conf is removed, we should be switching to a non-module folder model, not reporting it here")
        } else {
            instance.removeKeyInput(SOURCE_FILE_URLS, uri)
        }
    }
}

// TODO: This needs to handle dialects
class IndividualFilesFolderModel(private val folderUri: URI,
                                 private val languageClientProvider: LanguageClientProvider,
                                 private val executor: ExecutorService): InternalFolderModel {
    private val timestamper = AtomicLong(1L)
    private val latestResultTimestamps = ConcurrentHashMap<String, Long>()

    override fun setText(uri: String, text: String) {
        System.err.println("Running IndividualFilesFolderModel.setText")
        if (uri.endsWith("module.conf")) {
            // Ignore this case; we'll be switching to the other model type shortly
            return
        }
        // TODO: Could have bad characters to replace...
        val fakeModuleName = ModuleName("non-module", "non-module")
        val timestamp = timestamper.getAndIncrement()
        executor.submit {
            val validationResult = parseAndValidateString(text, uri, fakeModuleName, CURRENT_NATIVE_MODULE_VERSION)
            val allIssues = validationResult.getAllIssues()

            val diagnostics = collectDiagnostics(allIssues, listOf(uri))

            val client = languageClientProvider.getLanguageClient()

            // Check if we should actually send the diagnostics
            // TODO: It would be better if this synchronized per-name
            synchronized(this) {
                val existingTimestamp = latestResultTimestamps[uri] ?: 0L
                if (timestamp > existingTimestamp) {
                    latestResultTimestamps[uri] = timestamp
                    diagnostics.forEach { uri, diagnosticsList ->
                        // TODO: Maintain a list of the last set of diagnostics per document, avoid re-sending
                        // if nothing has changed
                        val diagnosticsParams = PublishDiagnosticsParams(uri, diagnosticsList)
                        client.publishDiagnostics(diagnosticsParams)
                    }
                }
            }
        }
    }

    override fun closeDocument(uri: String) {
        System.err.println("Running IndividualFilesFolderModel.closeDocument")
        // TODO: Anything to do here? Publish an empty list of diagnostics?
        val timestamp = timestamper.getAndIncrement()
        executor.submit {
            synchronized(this) {
                val existingTimestamp = latestResultTimestamps[uri] ?: 0L
                if (timestamp > existingTimestamp) {
                    latestResultTimestamps[uri] = timestamp
                    val client = languageClientProvider.getLanguageClient()
                    val diagnosticsParams = PublishDiagnosticsParams(uri, listOf())
                    client.publishDiagnostics(diagnosticsParams)
                }
            }
        }
    }
}

/*
 * This will contain our notion of what the current source files are for a given module and manage accepting updates
 * and triggering rebuilds that produce diagnostics.
 *
 * Note that this only handles a single directory (either a module or a directory of unrelated bare source files,
 * depending on whether a valid module.conf is present).
 */
//class OldSourcesFolderModel(private val folderUri: URI,
//                         private val languageClientProvider: LanguageClientProvider): SourcesFolderModel {
//    @Volatile private var folderState = SourcesFolderState(mapOf(), mapOf())
//
//    private val sourcesUsedForParsingResults = HashMap<String, DocumentSource>()
//    private val parsingResultsByDocumentName = HashMap<String, ParsingResult>()
//
//    @Volatile private var moduleInfo: ModuleInfo? = null // Note: Will be null if there is no valid module.conf
//
//    private val workQueue = LinkedBlockingQueue<() -> Unit>()
//
//    /*
//     * Note: One of the main reasons we have this kind of dedicated thread is so all the handling of the model's internal
//     * state is serialized and therefore easier to reason about. Another approach would be to use a shared thread pool
//     * among models, and use a monitor lock on this model for synchronization.
//     */
//    private val workerThread = Thread(fun() {
//        while (true) {
//            val workItem = workQueue.take()
//            try {
//                workItem()
//            } catch(e: Exception) {
//                e.printStackTrace(System.err)
//            }
//
//            // Wait until there are no more external changes to files coming in, then figure out which tasks to add to
//            // update the model
//            if (workQueue.isEmpty()) {
//                // Figure out needed tasks to update the model, based on the current folderState
//                addNeededModelUpdateTasks()
//            }
//        }
//    })
//
//    init {
//        // TODO: Shut this down at some point...
//        workerThread.start()
//        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(fun() {
//            workQueue.add(getScanFolderTask())
//        }, 0, 10, TimeUnit.SECONDS)
//    }
//
//    private fun getSourcesNeedingReparsing(): Set<String> {
//        val folderState = this.folderState
//        val sourcesNeedingReparsing = HashSet<String>()
//
//        System.err.println("Checking sources needing reparsing in the model for folder $folderUri...")
//
//        folderState.openFiles.forEach { openFileName, openFileText ->
//            val curSource = sourcesUsedForParsingResults[openFileName]
//            if (curSource == null || curSource !is DocumentSource.Client || curSource.text != openFileText) {
//                System.err.println("$openFileName needs reparsing based on open files")
//                sourcesNeedingReparsing.add(openFileName)
//            }
//        }
//
//        folderState.otherFiles.forEach { fileName, fileState ->
//            val curSource = sourcesUsedForParsingResults[fileName]
//            if (curSource == null || curSource !is DocumentSource.FileSystem || curSource.lastModifiedTime != fileState.lastModifiedTime) {
//                System.err.println("$fileName needs reparsing based on closed files: ${(curSource as? DocumentSource.FileSystem)?.lastModifiedTime} vs. ${fileState.lastModifiedTime}")
//                sourcesNeedingReparsing.add(fileName)
//            }
//        }
//
//        for (fileName in sourcesUsedForParsingResults.keys) {
//            if (!folderState.openFiles.containsKey(fileName) && !folderState.otherFiles.containsKey(fileName)) {
//                System.err.println("$fileName needs reparsing based on lack of any known files")
//                sourcesNeedingReparsing.add(fileName)
//            }
//        }
//        System.err.println("Sources needing reparsing: $sourcesNeedingReparsing")
//        return sourcesNeedingReparsing
//    }
//
//    private fun addNeededModelUpdateTasks() {
//        // TODO: Make this more fine-grained at some point in the future
//        val sourcesNeedingReparsing = getSourcesNeedingReparsing()
//        if (sourcesNeedingReparsing.isNotEmpty()) {
//            workQueue.add(getReparseAndRevalidateTask(sourcesNeedingReparsing))
//        }
//    }
//
//    private fun getReparseAndRevalidateTask(sourcesNeedingReparsing: Set<String>): () -> Unit {
//        return fun() {
//            val folderState = this.folderState
//
//            if (sourcesNeedingReparsing.contains("module.conf")) {
//                val openFileText = folderState.openFiles["module.conf"]
//                // Null if the file doesn't exist (can't be accessed, etc.)
//                val fileText: String? = if (openFileText != null) {
//                    sourcesUsedForParsingResults["module.conf"] = DocumentSource.Client(openFileText)
//                    openFileText
//                } else {
//                    val otherFileState = folderState.otherFiles["module.conf"]
//                    if (otherFileState != null) {
//                        sourcesUsedForParsingResults["module.conf"] = DocumentSource.FileSystem(otherFileState.lastModifiedTime)
//                        otherFileState.text
//                    } else {
//                        sourcesUsedForParsingResults.remove("module.conf")
//                        null
//                    }
//                }
//                if (fileText != null) {
//                    // TODO: Report errors more helpfully for invalid configs
//                    val configParsingResult = parseConfigFileString(fileText)
//                    if (configParsingResult is ModuleInfoParsingResult.Success) {
//                        this.moduleInfo = configParsingResult.info
//                    } else {
//                        if (configParsingResult is ModuleInfoParsingResult.Failure) {
//                            System.err.println("Config parsing failed: ${configParsingResult.error}")
//                        }
//                        this.moduleInfo = null
//                    }
//                } else {
//                    this.moduleInfo = null
//                }
//            }
//
//            val moduleInfo = this.moduleInfo
//            if (moduleInfo == null) {
//                // Treat each file as its own module
//                for (fileName in sourcesNeedingReparsing) {
//                    if (fileName == "module.conf") {
//                        continue
//                    }
//                    val openFileText = folderState.openFiles[fileName]
//                    // Null if the file doesn't exist (can't be accessed, etc.)
//                    val fileText: String? = if (openFileText != null) {
//                        sourcesUsedForParsingResults[fileName] = DocumentSource.Client(openFileText)
//                        openFileText
//                    } else {
//                        val otherFileState = folderState.otherFiles[fileName]
//                        if (otherFileState != null) {
//                            sourcesUsedForParsingResults[fileName] = DocumentSource.FileSystem(otherFileState.lastModifiedTime)
//                            otherFileState.text
//                        } else {
//                            sourcesUsedForParsingResults.remove(fileName)
//                            null
//                        }
//                    }
//
//
//                    if (fileText != null) {
//                        val parsingResult = parseString(fileText, getDocumentUriForFileName(fileName))
//                        parsingResultsByDocumentName[fileName] = parsingResult
//                    } else {
//                        parsingResultsByDocumentName.remove(fileName)
//                    }
//                }
//
//
//                parsingResultsByDocumentName.forEach { fileName, parsingResult ->
//                    val fakeModuleName = ModuleName("non-module", fileName.split(".")[0])
//                    val validationResult = validate(parsingResult, fakeModuleName, CURRENT_NATIVE_MODULE_VERSION, listOf())
//
//                    val diagnostics = collectDiagnostics(validationResult.getAllIssues(), listOf(getDocumentUriForFileName(fileName)))
//
//                    val client = languageClientProvider.getLanguageClient()
//                    diagnostics.forEach { uri, diagnosticsList ->
//                        // TODO: Maintain a list of the last set of diagnostics per document, avoid re-sending
//                        // if nothing has changed
//                        val diagnosticsParams = PublishDiagnosticsParams(uri, diagnosticsList)
//                        client.publishDiagnostics(diagnosticsParams)
//                    }
//                }
//            } else {
//                // We have a single module with a valid config
////                val combinedParsingResult = combineParsingResults(parsingResultsByDocumentName.values)
//                // TODO: We need dependencies here
//                val repository = getDefaultLocalRepository()
////                val combinedParsingResult = parseModuleDirectory(File(folderUri), repository)
//                // TODO: These might want to be more fine-grained tasks? Part of the model, etc.?
//                // TODO: Also catch and deal with errors here
//                val loadedDependencies = moduleInfo.dependencies.map {
//                    val uniqueId = repository.getModuleUniqueId(it, File(folderUri))
//                    repository.loadModule(uniqueId)
//                }
////                val validationResult = validate(combinedParsingResult, moduleInfo.name, CURRENT_NATIVE_MODULE_VERSION, loadedDependencies)
//                val validationResult = parseAndValidateModuleDirectory(File(folderUri), CURRENT_NATIVE_MODULE_VERSION, repository)
//
//                val documentUris = (parsingResultsByDocumentName.keys).toList().map(this::getDocumentUriForFileName)
//                val diagnostics = collectDiagnostics(validationResult.getAllIssues(), documentUris)
//
//                val client = languageClientProvider.getLanguageClient()
//                diagnostics.forEach { uri, diagnosticsList ->
//                    // TODO: Maintain a list of the last set of diagnostics per document, avoid re-sending
//                    // if nothing has changed
//                    val diagnosticsParams = PublishDiagnosticsParams(uri, diagnosticsList)
//                    client.publishDiagnostics(diagnosticsParams)
//                }
//            }
//
//        }
//    }
//
//    private fun getDocumentUriForFileName(fileName: String): String {
//        val documentUri = folderUri.resolve(fileName)
//        return documentUri.toASCIIString()
//    }
//
//    private fun getScanFolderTask(): () -> Unit {
//        return fun() {
//            if (!folderUri.scheme.equals("file", true)) {
//                error("Non-file path: $folderUri")
//            }
//            val folderPath = Paths.get(folderUri)
//            System.err.println("Folder path we're checking: $folderPath")
//            if (!Files.isDirectory(folderPath)) {
//                System.err.println("Looks like $folderPath isn't a directory")
//                this.folderState = SourcesFolderState(mapOf(), mapOf())
//                return
//            }
//            val openFilesState = this.folderState.openFiles
//            val oldOtherFilesState = this.folderState.otherFiles
//            val newOtherFilesState = HashMap<String, FileOnDiskState>()
//            Files.list(folderPath).forEach { filePath ->
//                val fileName = filePath.toFile().name
//                if (isRelevantFilename(fileName)) {
//                    // Don't load it if it's open in the client
//                    if (!openFilesState.containsKey(fileName)) {
//                        // Is it newer than the existing version we know about?
//                        val lastModifiedTime: FileTime = getLastModifiedTime(filePath)
//
//                        val oldFileState = oldOtherFilesState[fileName]
//                        if (oldFileState == null || oldFileState.lastModifiedTime < lastModifiedTime) {
//                            val loadedText = filePath.toFile().readText()
//                            newOtherFilesState[fileName] = FileOnDiskState(loadedText, lastModifiedTime)
//                        } else {
//                            newOtherFilesState[fileName] = oldFileState
//                        }
//                    }
//                }
//            }
//            this.folderState = SourcesFolderState(openFilesState, newOtherFilesState)
//        }
//    }
//
//    override fun openDocument(uri: String, text: String) {
//        workQueue.add(getOpenDocumentTask(uri, text))
//    }
//
//    private fun getOpenDocumentTask(uri: String, text: String): () -> Unit {
//        return fun() {
//            val oldOpenFilesState = this.folderState.openFiles
//            val oldOtherFilesState = this.folderState.otherFiles
//            val newOpenFilesState = HashMap<String, String>(oldOpenFilesState)
//            val newOtherFilesState = HashMap<String, FileOnDiskState>(oldOtherFilesState)
//
//            // TODO: It's probably better to convert to URI objects at an earlier level
//            val filePath = Paths.get(URI(uri))
//            val fileName = filePath.toFile().name
//
//            newOtherFilesState.remove(fileName)
//            newOpenFilesState.put(fileName, text)
//
//            this.folderState = SourcesFolderState(newOpenFilesState, newOtherFilesState)
//        }
//    }
//
//    override fun updateDocument(uri: String, text: String) {
//        workQueue.add(getUpdateDocumentTask(uri, text))
//    }
//
//    private fun getUpdateDocumentTask(uri: String, text: String): () -> Unit {
//        return fun() {
//            val oldOpenFilesState = this.folderState.openFiles
//            val newOpenFilesState = HashMap<String, String>(oldOpenFilesState)
//            val otherFilesState = this.folderState.otherFiles
//
//            val filePath = Paths.get(URI(uri))
//            val fileName = filePath.toFile().name
//
//            newOpenFilesState.put(fileName, text)
//
//            this.folderState = SourcesFolderState(newOpenFilesState, otherFilesState)
//        }
//    }
//
//    override fun closeDocument(uri: String) {
//        workQueue.add(getCloseDocumentTask(uri))
//    }
//
//    private fun getCloseDocumentTask(uri: String): () -> Unit {
//        return fun() {
//            val oldOpenFilesState = this.folderState.openFiles
//            val oldOtherFilesState = this.folderState.otherFiles
//            val newOpenFilesState = HashMap<String, String>(oldOpenFilesState)
//            val newOtherFilesState = HashMap<String, FileOnDiskState>(oldOtherFilesState)
//
//            val filePath = Paths.get(URI(uri))
//            val fileName = filePath.toFile().name
//
//            newOpenFilesState.remove(fileName)
//            if (isRelevantFilename(fileName) && Files.isRegularFile(filePath)) {
//                val lastModifiedTime = getLastModifiedTime(filePath)
//                val fileText = filePath.toFile().readText()
//                val fileState = FileOnDiskState(fileText, lastModifiedTime)
//                newOtherFilesState.put(fileName, fileState)
//            }
//
//            this.folderState = SourcesFolderState(newOpenFilesState, newOtherFilesState)
//        }
//    }
//}

private fun collectDiagnostics(issues: List<Issue>, documentUris: List<String>): Map<String, List<Diagnostic>> {
    val diagnosticsByUri = HashMap<String, ArrayList<Diagnostic>>()
    for (documentUri in documentUris) {
        diagnosticsByUri.put(documentUri, ArrayList<Diagnostic>())
    }
    diagnosticsByUri["unknownDocument"] = ArrayList<Diagnostic>()
    for (issue in issues) {
        val range = toLsp4jRange(issue.location)
        val severity = toLsp4jSeverity(issue.level)
        val diagnostic = Diagnostic(range, issue.message, severity, "semlang validator")
        // TODO: Temporary workaround...
        val documentUri = issue.location?.documentUri ?: "unknownDocument"
        if (!diagnosticsByUri.containsKey(documentUri)) {
            System.err.println("Document URI mismatch")
            System.err.println("Given list: $documentUris")
            System.err.println("From diagnostic: $documentUri")
        }
        diagnosticsByUri[documentUri]!!.add(diagnostic)
    }
    return diagnosticsByUri
}

private fun isRelevantFilename(fileName: String): Boolean {
    return fileName == "module.conf" || determineDialect(fileName) != null
}

private fun getLastModifiedTime(filePath: Path): FileTime {
    val lastModifiedTime: FileTime = Files.getAttribute(filePath, "lastModifiedTime") as FileTime
    return lastModifiedTime
}

private fun toLsp4jSeverity(level: IssueLevel): DiagnosticSeverity {
    return when (level) {
        IssueLevel.WARNING -> DiagnosticSeverity.Warning
        IssueLevel.ERROR -> DiagnosticSeverity.Error
    }
}

private fun toLsp4jRange(location: net.semlang.api.parser.Location?): Range {
    if (location == null) {
        return Range(Position(0, 0), Position(0, 1))
    } else {
        return Range(toLsp4jPosition(location.range.start), toLsp4jPosition(location.range.end))
    }
}

private fun toLsp4jPosition(position: net.semlang.api.parser.Position): Position {
    return Position(position.lineNumber - 1, position.column)
}
