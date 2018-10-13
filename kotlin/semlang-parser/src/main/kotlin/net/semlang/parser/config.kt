package net.semlang.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import net.semlang.api.*
import java.io.File
import java.io.Writer

// This is meant to incorporate all information that would appear in a module specification file
data class ModuleInfo(val name: ModuleName, val dependencies: List<ModuleNonUniqueId>)
data class UnvalidatedModule(val info: ModuleInfo, val contents: RawContext)

// TODO: In theory we could hook these directly to Jackson
fun parseConfigFile(file: File): ModuleInfoParsingResult {
    return parseConfigFileString(file.readText())
}

fun writeConfigFile(module: ValidatedModule, writer: Writer) {
    writer.write(writeConfigFileString(module))
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

        return ModuleInfoParsingResult.Success(ModuleInfo(name, dependencies))
    } catch(e: Exception) {
        return ModuleInfoParsingResult.Failure(e)
    }
}

fun writeConfigFileString(module: ValidatedModule): String {
    val mapper = ObjectMapper()
    val factory = mapper.nodeFactory

    val rootNode = ObjectNode(factory)
    rootNode.put("group", module.id.name.group)
    rootNode.put("module", module.id.name.module)

    val arrayNode = rootNode.putArray("dependencies")
    for (dependencyId in module.upstreamModules.keys) {
        writeDependencyNode(arrayNode.addObject(), dependencyId)
    }

    return mapper.writeValueAsString(rootNode)
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
