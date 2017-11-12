package net.semlang.languageserver

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture

class SemlangLanguageServer: LanguageServer {
    private val textDocumentService = SemlangTextDocumentService()
    private val workspaceService = SemlangWorkspaceService()

    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }

    override fun exit() {
        System.exit(0)
    }

    override fun initialize(params: InitializeParams?): CompletableFuture<InitializeResult> {
        // TODO: Configure capabilities
        val capabilities = ServerCapabilities()

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

}

class SemlangWorkspaceService: WorkspaceService {
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun symbol(params: WorkspaceSymbolParams?): CompletableFuture<MutableList<out SymbolInformation>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class SemlangTextDocumentService: TextDocumentService {
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

    override fun didOpen(params: DidOpenTextDocumentParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didSave(params: DidSaveTextDocumentParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun signatureHelp(position: TextDocumentPositionParams?): CompletableFuture<SignatureHelp> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didClose(params: DidCloseTextDocumentParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun formatting(params: DocumentFormattingParams?): CompletableFuture<MutableList<out TextEdit>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun didChange(params: DidChangeTextDocumentParams?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun references(params: ReferenceParams?): CompletableFuture<MutableList<out Location>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun resolveCodeLens(unresolved: CodeLens?): CompletableFuture<CodeLens> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

}
