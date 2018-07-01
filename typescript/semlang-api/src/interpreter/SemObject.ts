import { BigInteger } from "big-integer";
import * as bigInt from "big-integer";
import { Struct as StructDef, Interface, Block, Argument } from "../api/language";

export type SemObject = SemObject.Integer
 | SemObject.Natural
 | SemObject.Boolean
 | SemObject.List
 | SemObject.Maybe
 | SemObject.String
 | SemObject.Struct
 | SemObject.Instance
 | SemObject.FunctionBinding
 | SemObject.ListBuilder;
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
    export interface Instance {
        type: "instance";
        interface: Interface;
        methods: SemObject.FunctionBinding[];
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
    export interface InterfaceAdapterFunctionBinding {
        type: "interfaceAdapterBinding";
        interface: Interface;
        /**
         * Note: This should always be a SemObject.FunctionBinding[], but doing this makes working with
         * the general FunctionBinding type easier.
         */
        bindings: Array<SemObject | undefined>;
    }
    export type FunctionBinding = NamedFunctionBinding | InlineFunctionBinding | InterfaceAdapterFunctionBinding;
    export interface ListBuilder {
        type: "ListBuilder";
        contents: SemObject[];
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

export function instanceObject(interfaceDef: Interface, methods: SemObject.FunctionBinding[]): SemObject.Instance {
    return {
        type: "instance",
        interface: interfaceDef,
        methods,
    };
}

export function namedBindingObject(functionId: string, bindings: Array<SemObject | undefined>): SemObject.NamedFunctionBinding {
    return {
        type: "namedBinding",
        functionId,
        bindings
    }
}

export function inlineBindingObject(argumentNames: string[], bindings: Array<SemObject | undefined>, block: Block): SemObject.InlineFunctionBinding {
    return {
        type: "inlineBinding",
        argumentNames,
        bindings,
        block
    }
}

export function interfaceAdapterBindingObject(interfaceDef: Interface, bindings: SemObject.FunctionBinding[]): SemObject.InterfaceAdapterFunctionBinding {
    return {
        type: "interfaceAdapterBinding",
        bindings,
        interface: interfaceDef
    }
}

export function isFunctionBinding(object: SemObject): object is SemObject.FunctionBinding {
    return object.type === "namedBinding" || object.type === "inlineBinding" || object.type === "interfaceAdapterBinding";
}
