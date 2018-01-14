import { Struct as StructDef } from "../api/language";

export type SemObject = SemObject.Integer
 | SemObject.Natural
 | SemObject.Boolean
 | SemObject.List
 | SemObject.Try
 | SemObject.String
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
    export type Try = Try.Success | Try.Failure;
    export namespace Try {
        export interface Success {
            type: "Try";
            tryType: "success";
            value: SemObject;
        }
        export interface Failure {
            type: "Try";
            tryType: "failure";
        }
    }
    export interface String {
        type: "String";
        value: string;
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

export function successObject(innerValue: SemObject): SemObject.Try.Success {
    return {
        type: "Try",
        tryType: "success",
        value: innerValue,
    }
}

const FAILURE: SemObject.Try.Failure = {
    type: "Try",
    tryType: "failure",
};
export function failureObject(): SemObject.Try.Failure {
    return FAILURE;
}

export function stringObject(value: string): SemObject.String {
    return {
        type: "String",
        value,
    }
}

export function structObject(struct: StructDef, members: SemObject[]): SemObject.Struct {
    return {
        type: "struct",
        struct,
        members,
    }
}

export function bindingObject(functionId: string, bindings: Array<SemObject | undefined>): SemObject.FunctionBinding {
    return {
        type: "binding",
        functionId,
        bindings
    }
}
