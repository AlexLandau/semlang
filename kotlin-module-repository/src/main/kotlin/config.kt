package net.semlang.modules

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import net.semlang.api.ModuleId
import net.semlang.api.ValidatedModule
import java.io.File
import java.io.Writer

// TODO: In theory we could hook these directly to Jackson
fun parseConfigFile(file: File): ModuleInfo {
    return parseConfigFileString(file.readText())
}

fun writeConfigFile(module: ValidatedModule, writer: Writer) {
    writer.write(writeConfigFileString(module))
}

fun parseConfigFileString(text: String): ModuleInfo {
    val mapper = ObjectMapper()
    val rootNode = mapper.readTree(text)

    val group = rootNode.get("group").asText()
    val module = rootNode.get("module").asText()
    val version = rootNode.get("version").asText()
    val id = ModuleId(group, module, version)

    val dependencies = rootNode.get("dependencies").map(::parseDependencyNode)

    return ModuleInfo(id, dependencies)
}

fun writeConfigFileString(module: ValidatedModule): String {
    val mapper = ObjectMapper()
    val factory = mapper.nodeFactory

    val rootNode = ObjectNode(factory)
    rootNode.put("group", module.id.group)
    rootNode.put("module", module.id.module)
    rootNode.put("version", module.id.version)

    val arrayNode = rootNode.putArray("dependencies")
    module.upstreamModules.forEach { dependency ->
        writeDependencyNode(arrayNode.addObject(), dependency.id)
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
