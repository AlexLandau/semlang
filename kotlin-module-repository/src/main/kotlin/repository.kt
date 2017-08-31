package net.semlang.modules

import net.semlang.api.*
import net.semlang.parser.parseFile
import net.semlang.parser.validateContext
import net.semlang.parser.write
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

// This is meant to incorporate all information that would appear in a module specification file
data class ModuleInfo(val id: ModuleId, val dependencies: List<ModuleId>)

data class UnvalidatedModule(val id: ModuleId, val contents: RawContext)

/*
 * Larger design question: Do we treat the contents of the repository as validated or unvalidated?
 * For now, will treat as unvalidated for safety's sake (and because we'll catch more bugs that way)
 */
// TODO: This should be made thread-safe
class LocalRepository(val rootDirectory: File) {
    init {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }
        if (!rootDirectory.isDirectory) {
            error("The root of the local repository $rootDirectory is not a directory or could not be created")
        }
    }

    // TODO: Cache these
    private fun loadUnvalidatedModule(id: ModuleId): UnvalidatedModule {
        val containingDirectory = getDirectoryForId(id)

        // TODO: Nice-to-have: filename based on module name/version
        val rawContents = parseFile(File(containingDirectory, "module.sem"))

        return UnvalidatedModule(id, rawContents)
    }

    // TODO: We'll eventually have a ValidatedModule that is ValidatedContext plus ModuleInfo
    fun loadModule(id: ModuleId): ValidatedContext {
        // TODO: Handle dependencies
        val unvalidatedModule = loadUnvalidatedModule(id)

        return validateContext(unvalidatedModule.contents, listOf(getNativeContext()))
    }

    private fun getDirectoryForId(id: ModuleId): File {
        val groupDirectory = File(rootDirectory, id.group)
        val moduleDirectory = File(groupDirectory, id.module)
        val versionDirectory = File(moduleDirectory, id.version)

        return versionDirectory
    }

    fun unpublishIfPresent(moduleId: ModuleId) {
        val directory = getDirectoryForId(moduleId)
        if (directory.isDirectory) {
            val success = directory.deleteRecursively()
            if (!success) {
                error("Couldn't delete the directory $directory")
            }
        }
    }

    fun publish(moduleId: ModuleId, context: ValidatedContext) {
        val directory = getDirectoryForId(moduleId)
        directory.mkdirs()
        if (!directory.isDirectory) {
            error("Couldn't create the directory $directory")
        }

        // TODO: Also publish an info file with info on dependencies, etc.
        for (upstream in context.upstreamContexts) {
            if (ValidatedContext.NATIVE_CONTEXT != upstream) {
                error("We aren't handling dependencies yet...")
            }
        }

        // Publish the .sem file
        val sourceFile = File(directory, "module.sem")
        BufferedWriter(FileWriter(sourceFile)).use { writer ->
            write(context, writer)
        }
    }

}

fun getDefaultLocalRepository(): LocalRepository {
    val semlangFolder = File(System.getProperty("user.home"), ".semlang")
    val repoFolder = File(semlangFolder, "repo-0")
    return LocalRepository(repoFolder)
}
