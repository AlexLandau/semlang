package net.semlang.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import net.semlang.api.*
import java.io.File
import java.io.Writer

// This is meant to incorporate all information that would appear in a module specification file
data class ModuleInfo(val name: ModuleName, val dependencies: List<ModuleNonUniqueId>, val reexports: List<EntityRef>)
data class UnvalidatedModule(val info: ModuleInfo, val contents: RawContext)

// TODO: In theory we could hook these directly to Jackson
fun parseConfigFile(file: File): ModuleInfoParsingResult {
    return parseConfigFileString(file.readText())
}

fun writeConfigFile(module: ValidatedModule, writer: Writer) {
    writer.write(writeConfigFileString(module))
}

fun writeConfigFile(info: ModuleInfo, writer: Writer) {
    writer.write(writeConfigFileString(info))
}

sealed class ModuleInfoParsingResult {
    data class Success(val info: ModuleInfo): ModuleInfoParsingResult()
    data class Failure(val error: Exception): ModuleInfoParsingResult()
}

fun parseConfigFileString(text: String): ModuleInfoParsingResult {
    try {
        val mapper = ObjectMapper()
        val rootNode = mapper.readTree(text)

        val group = rootNode.get("group").asText()
        val module = rootNode.get("module").asText()
        val name = ModuleName(group, module)

        val dependencies = rootNode.get("dependencies").map(::parseDependencyNode)

        val reexports = rootNode.get("reexports").map(::parseReexport)

        return ModuleInfoParsingResult.Success(ModuleInfo(name, dependencies, reexports))
    } catch(e: Exception) {
        return ModuleInfoParsingResult.Failure(e)
    }
}

fun parseReexport(jsonNode: JsonNode): EntityRef {
    val text = jsonNode.textValue() ?: error("Expected a reexport value to be a string, but was: $jsonNode")

    val numColons = text.count { it == ':' }
    if (numColons == 0) {
        val namespacedName = text.split(".")
        return EntityRef(null, EntityId(namespacedName))
    } else if (numColons == 1) {
        val components = text.split(":")
        val moduleRef = ModuleRef(null, components[0], null)
        val namespacedName = components[1].split(".")
        return EntityRef(moduleRef, EntityId(namespacedName))
    } else if (numColons == 2) {
        val components = text.split(":")
        val moduleRef = ModuleRef(components[0], components[1], null)
        val namespacedName = components[2].split(".")
        return EntityRef(moduleRef, EntityId(namespacedName))
    } else {
        val components = text.split(":", limit = 3)
        val versionAndName = components[2]
        val lastColonIndex = versionAndName.lastIndexOf(':')
        val version = versionAndName.substring(0, lastColonIndex)
        val name = versionAndName.substring(lastColonIndex + 1)
        val moduleRef = ModuleRef(components[0], components[1], version)
        val namespacedName = name.split(".")
        return EntityRef(moduleRef, EntityId(namespacedName))
    }
}

private fun writeConfigFileString(info: ModuleInfo): String {
    val mapper = ObjectMapper()
    val factory = mapper.nodeFactory

    val rootNode = ObjectNode(factory)
    rootNode.put("group", info.name.group)
    rootNode.put("module", info.name.module)

    if (info.dependencies.isNotEmpty()) {
        val dependenciesArray = rootNode.putArray("dependencies")
        for (dependencyId in info.dependencies) {
            writeDependencyNode(dependenciesArray.addObject(), dependencyId)
        }
    }

    if (info.reexports.isNotEmpty()) {
        val reexportsArray = rootNode.putArray("reexports")
        for (reexport in info.reexports) {
            reexportsArray.add(getReexportString(reexport))
        }
    }

    return mapper.writeValueAsString(rootNode)
}

fun getReexportString(reexport: EntityRef): String {
    // Note: Keeping this as a separate function in case the toString() here starts adding quotes around the version
    return reexport.toString()
}

private fun writeConfigFileString(module: ValidatedModule): String {
    val info = getModuleInfo(module)
    return writeConfigFileString(info)
}

/**
 * Note: This gives dependencies their unique IDs, which may be unexpected behavior.
 */
fun getModuleInfo(module: ValidatedModule): ModuleInfo {
    val dependencies = module.upstreamModules.keys.map { it.asNonUniqueId() }
    val reexports = module.reexports
    return ModuleInfo(module.name, dependencies, reexports)
}

private fun parseDependencyNode(node: JsonNode): ModuleNonUniqueId {
    if (node.isTextual) {
        val splitContents = node.asText().split(":", limit = 3)
        if (splitContents.size != 3) {
            error("Expected three colon-separated components in a dependency listed in string format (group, module name, version)")
        }
        val group = splitContents[0]
        val module = splitContents[1]
        val version = splitContents[2]
        return ModuleNonUniqueId.fromStringTriple(group, module, version)
    }
    val group = node.get("group").asText()
    val module = node.get("module").asText()
    val version = node.get("version").asText()
    return ModuleNonUniqueId.fromStringTriple(group, module, version)
}

private fun writeDependencyNode(node: ObjectNode, id: ModuleUniqueId) {
    node.put("group", id.name.group)
    node.put("module", id.name.module)
    node.put("version", ModuleUniqueId.UNIQUE_VERSION_SCHEME_PREFIX + id.fake0Version)
}

private fun writeDependencyNode(node: ObjectNode, id: ModuleNonUniqueId) {
    node.put("group", id.name.group)
    node.put("module", id.name.module)
    node.put("version", id.versionScheme + ":" + id.version)
}
