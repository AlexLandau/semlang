package net.semlang.languageserver

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

interface LanguageClientProvider {
    fun getLanguageClient(): LanguageClient
}

class SemlangLanguageServer: LanguageServer, LanguageClientAware, LanguageClientProvider {
    private val languageClientHolder = CopyOnWriteArrayList<LanguageClient>()
    private val textDocumentService = SemlangTextDocumentService(this)
    private val workspaceService = SemlangWorkspaceService(this)

    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }

    override fun exit() {
        System.exit(0)
    }

    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> {
        // TODO: Configure capabilities
        val capabilities = ServerCapabilities()

        val textDocumentSyncOptions = TextDocumentSyncOptions()
        textDocumentSyncOptions.openClose = true
        textDocumentSyncOptions.change = TextDocumentSyncKind.Full // TODO: Incremental would be better
        capabilities.setTextDocumentSync(textDocumentSyncOptions)

        val result = InitializeResult(capabilities)
        return CompletableFuture.completedFuture(result)
    }

    override fun getWorkspaceService(): WorkspaceService {
        return workspaceService
    }

    override fun shutdown(): CompletableFuture<Any> {
        // TODO: Release unused resources
        return CompletableFuture.completedFuture(null)
    }

    override fun connect(client: LanguageClient) {
        if (!languageClientHolder.isEmpty()) {
            error("Expected empty languageClientHolder")
        }
        languageClientHolder.add(client)
    }

    override fun getLanguageClient(): LanguageClient {
        return languageClientHolder[0]
    }
}

class SemlangWorkspaceService(private val languageClientProvider: LanguageClientProvider): WorkspaceService {
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams?) {
        // TODO: This is the next thing to "implement"

        // If we ever have settings, update them here.
    }

    override fun symbol(params: WorkspaceSymbolParams?): CompletableFuture<MutableList<out SymbolInformation>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class SemlangTextDocumentService(private val languageClientProvider: LanguageClientProvider): TextDocumentService {
    val model = AllModulesModel(languageClientProvider)

    override fun resolveCompletionItem(unresolved: CompletionItem?): CompletableFuture<CompletionItem> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun codeAction(params: CodeActionParams?): CompletableFuture<MutableList<out Command>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun hover(position: TextDocumentPositionParams?): CompletableFuture<Hover> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun documentHighlight(position: TextDocumentPositionParams?): CompletableFuture<MutableList<out DocumentHighlight>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun definition(position: TextDocumentPositionParams?): CompletableFuture<MutableList<out Location>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun codeLens(params: CodeLensParams?): CompletableFuture<MutableList<out CodeLens>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun rename(params: RenameParams?): CompletableFuture<WorkspaceEdit> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun completion(position: TextDocumentPositionParams?): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun documentSymbol(params: DocumentSymbolParams?): CompletableFuture<MutableList<out SymbolInformation>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didOpen(params: DidOpenTextDocumentParams) {
        // TODO: This is the next one to implement

        val document = params.textDocument

        model.documentWasOpened(document.uri, document.text)
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didSave(params: DidSaveTextDocumentParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun signatureHelp(position: TextDocumentPositionParams?): CompletableFuture<SignatureHelp> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
        val document = params.textDocument
        model.documentWasClosed(document.uri)
    }

    override fun formatting(params: DocumentFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val document = params.textDocument

        model.documentWasUpdated(document.uri, params.contentChanges[0].text)
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun references(params: ReferenceParams?): CompletableFuture<MutableList<out Location>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun resolveCodeLens(unresolved: CodeLens?): CompletableFuture<CodeLens> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}
