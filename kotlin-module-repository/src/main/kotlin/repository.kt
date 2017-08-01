package net.semlang.modules

import semlang.api.UnvalidatedContext
import semlang.api.ValidatedContext
import semlang.api.getNativeContext
import semlang.parser.parseFile
import semlang.parser.validateContext
import write
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.regex.Pattern

private val ILLEGAL_CHAR_PATTERN = Pattern.compile("[^0-9a-zA-Z_.-]")

// TODO: kotlin-api or kotlin-module-api?
data class ModuleId(val group: String, val module: String, val version: String) {
    init {
        // TODO: Consider if these restrictions can/should be relaxed
        for ((string, stringType) in listOf(group to "group",
                                            module to "name",
                                            version to "version"))
        if (ILLEGAL_CHAR_PATTERN.matcher(string).find()) {
            throw IllegalArgumentException("Illegal character in module $stringType '$string'; only letters, numbers, dots, hyphens, and underscores are allowed.")
        }
    }
}

// This is meant to incorporate all information that would appear in a module specification file
data class ModuleInfo(val id: ModuleId, val dependencies: List<ModuleId>)

data class UnvalidatedModule(val id: ModuleId, val contents: UnvalidatedContext)

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
    val repoFolder = File(semlangFolder, "repo0")
    return LocalRepository(repoFolder)
}
