package net.semlang.parser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import java.io.File
import java.io.Writer

// This is meant to incorporate all information that would appear in a module specification file
data class ModuleInfo(val id: ModuleId, val dependencies: List<ModuleId>)

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
        val version = rootNode.get("version").asText()
        val id = ModuleId(group, module, version)

        val dependencies = rootNode.get("dependencies").map(::parseDependencyNode)

        return ModuleInfoParsingResult.Success(ModuleInfo(id, dependencies))
    } catch(e: Exception) {
        return ModuleInfoParsingResult.Failure(e)
    }
}

fun writeConfigFileString(module: ValidatedModule): String {
    val mapper = ObjectMapper()
    val factory = mapper.nodeFactory

    val rootNode = ObjectNode(factory)
    rootNode.put("group", module.id.group)
    rootNode.put("module", module.id.module)
    rootNode.put("version", module.id.version)

    val arrayNode = rootNode.putArray("dependencies")
    module.upstreamModules.keys.forEach { dependencyId ->
        writeDependencyNode(arrayNode.addObject(), dependencyId)
    }

    return mapper.writeValueAsString(rootNode)
}

private fun parseDependencyNode(node: JsonNode): ModuleId {
    val group = node.get("group").asText()
    val module = node.get("module").asText()
    val version = node.get("version").asText()
    return ModuleId(group, module, version)
}

private fun writeDependencyNode(node: ObjectNode, id: ModuleId) {
    node.put("group", id.group)
    node.put("module", id.module)
    node.put("version", id.version)
}
