package net.semlang.modules

import net.semlang.api.*
import net.semlang.parser.*
import net.semlang.validator.validateModule
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

interface ModuleRepository {
    fun loadModule(id: ModuleUniqueId): ValidatedModule
    fun getModuleUniqueId(dependencyId: ModuleNonUniqueId, callingModuleDirectory: File?): ModuleUniqueId
}

/*
 * Larger design question: Do we treat the contents of the repository as validated or unvalidated?
 * For now, will treat as unvalidated for safety's sake (and because we'll catch more bugs that way)
 */
// TODO: This should be made thread-safe
// TODO: Maybe also check that the module's contents match its version when loading it?
class LocalRepository(private val rootDirectory: File): ModuleRepository {
    init {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }
        if (!rootDirectory.isDirectory) {
            error("The root of the local repository $rootDirectory is not a directory or could not be created")
        }
    }

    // TODO: Cache these
    // TODO: Also check the hash is what we expect
    private fun loadUnvalidatedModule(id: ModuleUniqueId): UnvalidatedModule {
        val containingDirectory = getDirectoryForId(id)

        // TODO: Nice-to-have: filename based on module name/version
        val rawContents = parseFile(File(containingDirectory, "module.sem")).assumeSuccess()

        val confParsingResult = parseConfigFile(File(containingDirectory, "module")) as? ModuleInfoParsingResult.Success ?: error("Couldn't parse the module config")
        val moduleInfo = confParsingResult.info

        return UnvalidatedModule(moduleInfo, rawContents)
    }

    // TODO: Add support for file: dependencies
    override fun loadModule(id: ModuleUniqueId): ValidatedModule {
        return loadModuleInternal(id, HashSet())
    }
    private fun loadModuleInternal(id: ModuleUniqueId, alreadyLoading: Set<ModuleUniqueId>): ValidatedModule {
        val unvalidatedModule = loadUnvalidatedModule(id)

        val loadedDependencies = unvalidatedModule.info.dependencies.map { dependencyId ->
            // TODO: When we store a module in the cache, we should switch its dependencies to unique versions
            val uniqueId = dependencyId.requireUnique()
            if (alreadyLoading.contains(uniqueId)) {
                error("Circular dependency involving $dependencyId")
            }
            loadModuleInternal(uniqueId, alreadyLoading + uniqueId)
        }

        return validateModule(unvalidatedModule.contents, unvalidatedModule.info.name, CURRENT_NATIVE_MODULE_VERSION, loadedDependencies).assumeSuccess()
    }

    override fun getModuleUniqueId(dependencyId: ModuleNonUniqueId, callingModuleDirectory: File?): ModuleUniqueId {
        return when (dependencyId.versionScheme) {
            "fake0" -> {
                ModuleUniqueId(dependencyId.name, dependencyId.version)
            }
            "file" -> {
                // TODO: Figure out what kind of caching is appropriate for file system-based modules
                val directory = File(callingModuleDirectory, dependencyId.version)
                if (!directory.isDirectory) {
                    error("Module $dependencyId not found at location $directory: directory does not exist or is not a directory")
                }
                // TODO: Refactor
                val parsingResult = parseModuleDirectory(directory, this)
                if (parsingResult !is ModuleDirectoryParsingResult.Success) {
                    error("Failure for module $dependencyId: Parsing failed: ${(parsingResult as ModuleDirectoryParsingResult.Failure).errors}")
                }
                val upstreamModules = parsingResult.module.info.dependencies.map { transDepId ->
                    // TODO: Add circular dependency protections
                    val transDepUniqueId = this.getModuleUniqueId(transDepId, directory)
                    this.loadModule(transDepUniqueId)
                }
                val uniqueVersion = computeFake0Version(parsingResult.module.contents, upstreamModules)
                val uniqueId = ModuleUniqueId(dependencyId.name, uniqueVersion)
                publishUnvalidated(parsingResult.module, uniqueId)
                return uniqueId
            }
            else -> {
                error("Unrecognized version scheme ${dependencyId.versionScheme} in module $dependencyId")
            }
        }
    }

    private fun getDirectoryForId(id: ModuleUniqueId): File {
        val groupDirectory = File(rootDirectory, id.name.group)
        val moduleDirectory = File(groupDirectory, id.name.module)
        val versionDirectory = File(moduleDirectory, id.fake0Version)

        return versionDirectory
    }

    fun unpublishIfPresent(moduleId: ModuleUniqueId) {
        val directory = getDirectoryForId(moduleId)
        if (directory.isDirectory) {
            val success = directory.deleteRecursively()
            if (!success) {
                error("Couldn't delete the directory $directory")
            }
        }
    }

    // TODO: Deconflict with publishUnvalidated
    fun publish(module: ValidatedModule) {
        val directory = getDirectoryForId(module.id)
        directory.mkdirs()
        if (!directory.isDirectory) {
            error("Couldn't create the directory $directory")
        }

        // Publish the .sem file
        val sourceFile = File(directory, "module.sem")
        BufferedWriter(FileWriter(sourceFile)).use { writer ->
            write(module, writer)
        }

        // Publish the info file
        val infoFile = File(directory, "module")
        BufferedWriter(FileWriter(infoFile)).use { writer ->
            writeConfigFile(module, writer)
        }
    }

    private fun publishUnvalidated(module: UnvalidatedModule, uniqueId: ModuleUniqueId) {
        val directory = getDirectoryForId(uniqueId)
        directory.mkdirs()
        if (!directory.isDirectory) {
            error("Couldn't create the directory $directory")
        }

        // Publish the .sem file
        val sourceFile = File(directory, "module.sem")
        BufferedWriter(FileWriter(sourceFile)).use { writer ->
            write(module.contents, writer)
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
