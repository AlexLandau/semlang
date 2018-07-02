import { Context, Function, Module, Struct, Interface, Union } from "../api/language";

// TODO: Have something that actually does validation
export function toModule(context: Context): Module {
    const functionsMap: {[id: string]: Function} = {};
    for (const fn of context.functions) {
        functionsMap[fn.id] = fn;
    }
    const structsMap: {[id: string]: Struct} = {};
    for (const struct of context.structs) {
        structsMap[struct.id] = struct;
    }
    const interfacesMap: {[id: string]: Interface} = {};
    const interfacesByAdapterIdMap: {[adapterId: string]: Interface} = {};
    for (const interfac of context.interfaces){
        interfacesMap[interfac.id] = interfac;
        interfacesByAdapterIdMap[interfac.id + ".Adapter"] = interfac;
    }
    const unionsMap: {[id: string]: Union} = {};
    const unionsByWhenIdMap: {[whenId: string]: Union} = {};
    const unionsByOptionIdMap: {[optionId: string]: [Union, number]} = {};
    for (const union of context.unions) {
        unionsMap[union.id] = union;
        unionsByWhenIdMap[union.id + ".when"] = union;
        union.options.forEach((option, optionIndex) => {
            unionsByOptionIdMap[union.id + "." + option.name] = [union, optionIndex];
        });
    }

    return {
        functions: functionsMap,
        structs: structsMap,
        interfaces: interfacesMap,
        interfacesByAdapterId: interfacesByAdapterIdMap,
        unions: unionsMap,
        unionsByWhenId: unionsByWhenIdMap,
        unionsByOptionId: unionsByOptionIdMap,
    }
}
