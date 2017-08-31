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

// TODO: Consider merging this concept with that of the context
data class UnvalidatedModule(val info: ModuleInfo, val contents: RawContext)
data class ValidatedModule(val info: ModuleInfo, val context: ValidatedContext)

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

        val moduleInfo = parseConfigFile(File(containingDirectory, "module"))

        return UnvalidatedModule(moduleInfo, rawContents)
    }

    fun loadModule(id: ModuleId): ValidatedModule {
        return loadModuleInternal(id, HashSet())
    }
    private fun loadModuleInternal(id: ModuleId, alreadyLoading: Set<ModuleId>): ValidatedModule {
        val unvalidatedModule = loadUnvalidatedModule(id)

        val loadedDependencies = unvalidatedModule.info.dependencies.map { dependencyId ->
            if (alreadyLoading.contains(dependencyId)) {
                error("Circular dependency involving $dependencyId")
            }
            loadModuleInternal(dependencyId, alreadyLoading + dependencyId).context
        }

        // TODO: Is there some more elegant option than the "+ native context" here?
        val validatedContext = validateContext(unvalidatedModule.contents, loadedDependencies + getNativeContext())
        return ValidatedModule(unvalidatedModule.info, validatedContext)
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

    fun publish(module: ValidatedModule) {
        val directory = getDirectoryForId(module.info.id)
        directory.mkdirs()
        if (!directory.isDirectory) {
            error("Couldn't create the directory $directory")
        }

        // Publish the .sem file
        val sourceFile = File(directory, "module.sem")
        BufferedWriter(FileWriter(sourceFile)).use { writer ->
            write(module.context, writer)
        }

        // Publish the info file
        val infoFile = File(directory, "module")
        BufferedWriter(FileWriter(infoFile)).use { writer ->
            writeConfigFile(module.info, writer)
        }
    }

}

fun getDefaultLocalRepository(): LocalRepository {
    val semlangFolder = File(System.getProperty("user.home"), ".semlang")
    val repoFolder = File(semlangFolder, "repo-0")
    return LocalRepository(repoFolder)
}
