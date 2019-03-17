import { BigInteger } from "big-integer";
import { Struct as StructDef, Block } from "../api/language";
import { NativeStructs } from "./nativeFunctions";

export type SemObject = SemObject.Integer
 | SemObject.Natural
 | SemObject.Boolean
 | SemObject.List
 | SemObject.Maybe
 | SemObject.String
 | SemObject.Struct
 | SemObject.Union
 | SemObject.FunctionBinding
 | SemObject.ListBuilder
 | SemObject.Var
 ;
export namespace SemObject {
    export interface Integer {
        type: "Integer";
        value: BigInteger;
    }
    export interface Natural {
        type: "Natural";
        value: BigInteger;
    }
    export interface Boolean {
        type: "Boolean";
        value: boolean;
    }
    export interface List {
        type: "List";
        contents: SemObject[];
    }
    export type Maybe = Maybe.Success | Maybe.Failure;
    export namespace Maybe {
        export interface Success {
            type: "Maybe.Success";
            value: SemObject;
        }
        export interface Failure {
            type: "Maybe.Failure";
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
    export interface Union {
        type: "union";
        optionIndex: number;
        object?: SemObject;
    }
    export interface NamedFunctionBinding {
        type: "namedBinding";
        functionId: string;
        bindings: Array<SemObject | undefined>;
    }
    export interface InlineFunctionBinding {
        type: "inlineBinding";
        argumentNames: string[];
        block: Block;
        bindings: Array<SemObject | undefined>;
    }
    export type FunctionBinding = NamedFunctionBinding | InlineFunctionBinding;
    export interface ListBuilder {
        type: "ListBuilder";
        contents: SemObject[];
    }
    export interface Var {
        type: "Var";
        value: SemObject;
    }
}

export function integerObject(value: BigInteger): SemObject.Integer {
    return {
        type: "Integer",
        value
    };
}

export function naturalObject(value: BigInteger): SemObject.Natural {
    if (value.isNegative()) {
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

export function listBuilderObject(contents: SemObject[]): SemObject.ListBuilder {
    return {
        type: "ListBuilder",
        contents
    };
}

export function successObject(innerValue: SemObject): SemObject.Maybe.Success {
    return {
        type: "Maybe.Success",
        value: innerValue,
    }
}

const FAILURE: SemObject.Maybe.Failure = {
    type: "Maybe.Failure",
};
export function failureObject(): SemObject.Maybe.Failure {
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

export function unionObject(optionIndex: number, object?: SemObject): SemObject.Union {
    return {
        type: "union",
        optionIndex,
        object,
    };
}

export function namedBindingObject(functionId: string, bindings: Array<SemObject | undefined>): SemObject.NamedFunctionBinding {
    return {
        type: "namedBinding",
        functionId,
        bindings
    }
}

export function varObject(initialValue: SemObject): SemObject.Var {
    return {
        type: "Var",
        value: initialValue
    }
}

const VOID_OBJECT = structObject(NativeStructs["Void"], []);
export function voidObject(): SemObject.Struct {
    return VOID_OBJECT;
}

export function inlineBindingObject(argumentNames: string[], bindings: Array<SemObject | undefined>, block: Block): SemObject.InlineFunctionBinding {
    return {
        type: "inlineBinding",
        argumentNames,
        bindings,
        block
    }
}

export function isFunctionBinding(object: SemObject): object is SemObject.FunctionBinding {
    return object.type === "namedBinding" || object.type === "inlineBinding";
}
