import { Context, Function, Module, Struct, Interface } from "../api/language";

// TODO: Have something that actually does validation
export function toModule(context: Context): Module {
    const functionsMap: {[id: string]: Function} = {};
    context.functions.forEach((fn) => {
        functionsMap[fn.id] = fn;
    });
    const structsMap: {[id: string]: Struct} = {};
    context.structs.forEach((struct) => {
        structsMap[struct.id] = struct;
    });
    const interfacesMap: {[id: string]: Interface} = {};
    const interfacesByAdapterIdMap: {[adapterId: string]: Interface} = {};
    context.interfaces.forEach((interfac) => {
        interfacesMap[interfac.id] = interfac;
        interfacesByAdapterIdMap[interfac.id + ".Adapter"] = interfac;
    });

    return {
        functions: functionsMap,
        structs: structsMap,
        interfaces: interfacesMap,
        interfacesByAdapterId: interfacesByAdapterIdMap,
    }
}
