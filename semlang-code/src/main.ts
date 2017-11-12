
// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';

// import { workspace, ExtensionContext } from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind, Executable } from 'vscode-languageclient';

// this method is called when your extension is activated
// your extension is activated the very first time the command is executed
export function activate(context: vscode.ExtensionContext) {

    // Use the console to output diagnostic information (console.log) and errors (console.error)
    // This line of code will only be executed once when your extension is activated
    console.log('Activated semlang plugin');

    

    // The command has been defined in the package.json file
    // Now provide the implementation of the command with  registerCommand
    // The commandId parameter must match the command field in package.json
    var disposable1 = vscode.commands.registerCommand('extension.sayHello', () => {
        // The code you place here will be executed every time your command is executed

        // Display a message box to the user
        vscode.window.showInformationMessage('Hello World!');
    });

    context.subscriptions.push(disposable1);
// }

// export function activate(context: ExtensionContext) {

    // The server is implemented in node
    // let serverModule = context.asAbsolutePath(path.join('server', 'server.js'));
    // The debug options for the server
    // let debugOptions = { execArgv: ["--nolazy", "--debug=6009"] };

    let languageServerExecutable: Executable = {
        command: "java",
        args: ["-jar", "C:/Users/Alex/code/semlang/kotlin-language-server/build/libs/kotlin-language-server-all.jar"]
    }
    
    // If the extension is launched in debug mode then the debug server options are used
    // Otherwise the run options are used
    let serverOptions: ServerOptions = {
        run: languageServerExecutable,
        debug: languageServerExecutable
    }
    // export interface Executable {
    //     command: string;
    //     args?: string[];
    //     options?: ExecutableOptions;
    // }

    // Options to control the language client
    let clientOptions: LanguageClientOptions = {
        // Register the server for plain text documents
        documentSelector: [{scheme: 'file', language: 'semlang'}],
        synchronize: {
            // Synchronize the setting section 'lspSample' to the server
            configurationSection: 'lspSample',
            // Notify the server about file changes to '.clientrc files contain in the workspace
            fileEvents: vscode.workspace.createFileSystemWatcher('**/.clientrc')
        }
    }

    // Create the language client and start the client.
    let disposable = new LanguageClient('semlang', 'Semlang Plugin', serverOptions, clientOptions).start();

    // Push the disposable to the context's subscriptions so that the
    // client can be deactivated on extension deactivation
    context.subscriptions.push(disposable);
}
