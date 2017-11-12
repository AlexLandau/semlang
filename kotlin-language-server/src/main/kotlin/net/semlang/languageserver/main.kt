package net.semlang.languageserver

import org.eclipse.lsp4j.launch.LSPLauncher

fun main(args: Array<String>) {
    val server = SemlangLanguageServer()
    LSPLauncher.createServerLauncher(server,
            System.`in`,
            System.out).startListening()
}
