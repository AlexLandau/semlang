package net.semlang.parser.test

import net.semlang.api.*
import net.semlang.validator.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TypesMetadataTest {
    private val moduleId = ModuleUniqueId(ModuleName("semlang", "semlang-test"), "fake")
    // TODO: Probably make this easier to instantiate
    private val naturalType = UnvalidatedType.NamedType(NativeStruct.NATURAL.id.asRef(), false)

    @Test
    fun testSingleLinkTypeChain() {
        val myIntId = EntityId.of("MyInt")
        val resolvedMyInt = ResolvedEntityRef(moduleId, myIntId)
        val typesInfo = getTestTypesInfo(mapOf(
             myIntId to TypeInfo.Struct(
                 myIntId,
                 listOf(),
                 mapOf("integer" to UnvalidatedType.Integer()),
                 false,
                 false,
                 null
             )
        ))
        val metadata = getTypesMetadata(typesInfo)

        assertEquals(TypeChain(
            UnvalidatedType.NamedType(resolvedMyInt.toUnresolvedRef(), false),
            listOf(TypeChainLink("integer", UnvalidatedType.Integer()))),
            metadata.typeChains[resolvedMyInt])
    }

    @Test
    fun testTwoLinkTypeChain() {
        val myBitId = EntityId.of("MyBit")
        val resolvedMyBit = ResolvedEntityRef(moduleId, myBitId)
        val typesInfo = getTestTypesInfo(mapOf(
            myBitId to TypeInfo.Struct(
                myBitId,
                listOf(),
                mapOf("natural" to naturalType),
                true,
                false,
                null
            )
        ))
        val metadata = getTypesMetadata(typesInfo)

        assertEquals(TypeChain(
            UnvalidatedType.NamedType(resolvedMyBit.toUnresolvedRef(), false),
            listOf(
                TypeChainLink("natural", naturalType),
                TypeChainLink("integer", UnvalidatedType.Integer())
            )),
            metadata.typeChains[resolvedMyBit])
    }

    @Test
    fun testUnknownTypeInChain() {
        val myTypeId = EntityId.of("MyType")
        val resolvedMyType = ResolvedEntityRef(moduleId, myTypeId)
        val typesInfo = getTestTypesInfo(mapOf(
            myTypeId to TypeInfo.Struct(
                myTypeId,
                listOf(),
                mapOf("cycle" to UnvalidatedType.NamedType(EntityId.of("UnknownType").asRef(), false)),
                false,
                false,
                null
            )
        ))
        val metadata = getTypesMetadata(typesInfo)

        assertFalse(metadata.typeChains.containsKey(resolvedMyType))
        // TODO: Add error info to metadata, validate here
    }

    @Test
    fun testOneLinkCycleChain() {
        val myTypeId = EntityId.of("MyType")
        val resolvedMyType = ResolvedEntityRef(moduleId, myTypeId)
        val typesInfo = getTestTypesInfo(mapOf(
            myTypeId to TypeInfo.Struct(
                myTypeId,
                listOf(),
                mapOf("cycle" to UnvalidatedType.NamedType(myTypeId.asRef(), false)),
                false,
                false,
                null
            )
        ))
        val metadata = getTypesMetadata(typesInfo)

        assertFalse(metadata.typeChains.containsKey(resolvedMyType))
        // TODO: Add error info to metadata, validate here
    }

    @Test
    fun testTwoLinkCycleChain() {
        val myTypeAId = EntityId.of("MyTypeA")
        val myTypeBId = EntityId.of("MyTypeB")
        val resolvedMyTypeA = ResolvedEntityRef(moduleId, myTypeAId)
        val resolvedMyTypeB = ResolvedEntityRef(moduleId, myTypeBId)
        val typesInfo = getTestTypesInfo(mapOf(
            myTypeAId to TypeInfo.Struct(
                myTypeAId,
                listOf(),
                mapOf("b" to UnvalidatedType.NamedType(myTypeBId.asRef(), false)),
                false,
                false,
                null
            ),
            myTypeBId to TypeInfo.Struct(
                myTypeBId,
                listOf(),
                mapOf("a" to UnvalidatedType.NamedType(myTypeAId.asRef(), false)),
                false,
                false,
                null
            )
        ))
        val metadata = getTypesMetadata(typesInfo)

        assertFalse(metadata.typeChains.containsKey(resolvedMyTypeA))
        assertFalse(metadata.typeChains.containsKey(resolvedMyTypeB))
        // TODO: Add error info to metadata, validate here
    }

    private fun getTestTypesInfo(localStructs: Map<EntityId, TypeInfo>): TypesInfo {
        return getTypesInfoFromSummary(
            TypesSummary(
                localTypes = localStructs,
                localFunctions = mapOf(),
                duplicateTypeIds = setOf(),
                duplicateFunctionIds = setOf()
            ),
            moduleId,
            upstreamModules = listOf(),
            moduleVersionMappings = mapOf(),
            recordIssue = {}
        )
    }
}