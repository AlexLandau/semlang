import { Struct as StructDef } from "../api/language";

export type SemObject = SemObject.Integer
 | SemObject.Natural
 | SemObject.Boolean
 | SemObject.List
 | SemObject.Struct
 | SemObject.FunctionBinding;
export namespace SemObject {
    export interface Integer {
        type: "Integer";
        value: number; // TODO: Fix this
    }
    export interface Natural {
        type: "Natural";
        value: number; // TODO: Fix this
    }
    export interface Boolean {
        type: "Boolean";
        value: boolean;
    }
    export interface List {
        type: "List";
        contents: SemObject[];
    }
    export interface Struct {
        type: "struct";
        struct: StructDef;
        members: SemObject[];
    }
    export interface FunctionBinding {
        type: "binding";
        functionId: string;
        bindings: Array<SemObject | undefined>;
    }
}

export function integerObject(value: number): SemObject.Integer {
    return {
        type: "Integer",
        value
    };
}

export function naturalObject(value: number): SemObject.Natural {
    if (value < 0) {
        throw new Error(`Invalid natural value ${value}`);
    }
    return {
        type: "Natural",
        value
    };
}

export function booleanObject(value: boolean): SemObject.Boolean {
    return {
        type: "Boolean",
        value
    };
}

export function listObject(contents: SemObject[]): SemObject.List {
    return {
        type: "List",
        contents
    };
}

export function bindingObject(functionId: string, bindings: Array<SemObject | undefined>): SemObject.FunctionBinding {
    return {
        type: "binding",
        functionId,
        bindings
    }
}
