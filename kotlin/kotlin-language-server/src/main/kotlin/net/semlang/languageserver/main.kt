package net.semlang.languageserver

import org.eclipse.lsp4j.launch.LSPLauncher


fun main(args: Array<String>) {
    val server = SemlangLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server,
            System.`in`,
            System.out)

    val client = launcher.getRemoteProxy()
    server.connect(client)

    launcher.startListening()
}
