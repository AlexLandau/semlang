
import * as vscode from 'vscode';
import * as path from 'path';

import { LanguageClient, LanguageClientOptions, ServerOptions, Executable } from 'vscode-languageclient';

// This is called when this extension is activated
export function activate(context: vscode.ExtensionContext) {

    console.log('Activated semlang plugin');

    const jarPath = path.join(context.extensionPath, "semlang-language-server.jar");
    console.log("Expected jar path is: " + jarPath);
    let languageServerExecutable: Executable = {
        command: "java",
        args: ["-jar", jarPath]
    }
    
    let serverOptions: ServerOptions = {
        run: languageServerExecutable,
        debug: languageServerExecutable
    }

    // Options to control the language client
    let clientOptions: LanguageClientOptions = {
        // Register the server for plain text documents
        documentSelector: [{scheme: 'file', language: 'semlang'}],
        synchronize: {
            // Synchronize the setting section 'lspSample' to the server
            // TODO: Set up actual configuration
            configurationSection: 'lspSample',
            // Notify the server about file changes to '.clientrc files contain in the workspace
            // fileEvents: vscode.workspace.createFileSystemWatcher('**/.clientrc')
        }
    }

    // Create the language client and start the client.
    let disposable = new LanguageClient('semlang', 'Semlang Plugin', serverOptions, clientOptions).start();

    // Push the disposable to the context's subscriptions so that the
    // client can be deactivated on extension deactivation
    context.subscriptions.push(disposable);

    const openTranspilationProvider = vscode.workspace.registerTextDocumentContentProvider(
        "semlangtrans",
        {
            provideTextDocumentContent: (uri, cancellationToken) => {
                console.log(`The uri is: ${uri.toString()}`);
                // TODO: Implement
                // TODO: How to auto-update? Probably related to onDidChange
                return `Transpilation goes here, uri was ${uri.toString()}`;
            }
        });
    context.subscriptions.push(openTranspilationProvider);

    context.subscriptions.push(vscode.commands.registerTextEditorCommand("semlang.openTranspilation", async (textEditor) => {
        let newUriString = "semlangtrans:" + textEditor.document.uri.toString().replace(":", "/");
        let uri = vscode.Uri.parse(newUriString);
        let doc = await vscode.workspace.openTextDocument(uri); // calls back into the provider
        await vscode.window.showTextDocument(doc, { preview: false, viewColumn: vscode.ViewColumn.Beside });
    }));
}
