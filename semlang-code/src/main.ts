
import * as vscode from 'vscode';
import * as path from 'path';

import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind, Executable } from 'vscode-languageclient';

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
}
