
// Note: This is the same type as the Kotlin toJson outputs
export interface Context {
    semlang: string; // Language variant
    version: string; // Language/format version
    functions: Function[];
    structs: Struct[];
    interfaces: Interface[];
}

// TODO: Support upstream modules
export interface Module {
    functions: { [id: string]: Function }
    structs: { [id: string]: Struct }
    interfaces: { [id: string]: Interface }
    interfacesByAdapterId: { [adapterId: string]: Interface }
}

export interface Function {
    id: string;
    annotations?: Annotation[];
    typeParameters?: string[];
    arguments: Argument[];
    returnType: Type;
    block: Block;
}

export interface Argument {
    name: string;
    type: Type;
}

export interface Struct {
    id: string;
    typeParameters?: string[];
    annotations?: Annotation[];
    members: Member[];
    requires?: Block;
}

export interface Member {
    name: string;
    type: Type;
}

export interface Interface {
    id: string;
    annotations?: Annotation[];
    typeParameters?: string[];
    methods: Method[];
}

export interface Method {
    name: string;
    typeParameters?: string[];
    arguments: Argument[];
    returnType: Type;
}

export type Block = BlockElement[];

export interface Annotation {
    name: string;
    values: AnnotationArgument[];
}

// The following is disallowed, so I need to cheat a little...
// export type AnnotationArgument = string | AnnotationArgument[];
export type AnnotationArgument = string | (string | any[])[];

// TODO: Maybe reconsider these two and Block?
export type BlockElement = Assignment | { return: Expression };

export function isAssignment(blockElement: BlockElement): blockElement is Assignment {
    return "let" in blockElement;
}

export interface Assignment {
    let: string;
    be: Expression;
}

export type Expression = Expression.Variable
 | Expression.IfThen
 | Expression.NamedFunctionCall
 | Expression.ExpressionFunctionCall
 | Expression.Literal
 | Expression.ListLiteral
 | Expression.Follow
 | Expression.NamedFunctionBinding
 | Expression.ExpressionFunctionBinding
 | Expression.InlineFunction;

export namespace Expression {
    export interface Variable {
        type: "var";
        var: string;
    }
    export interface IfThen {
        type: "ifThen";
        if: Expression;
        then: Block;
        else: Block;
    }
    export interface NamedFunctionCall {
        type: "namedCall";
        function: string;
        chosenParameters: Type[];
        arguments: Expression[];
    }
    export interface ExpressionFunctionCall {
        type: "expressionCall";
        //TODO: rename this, probably
        expression: Expression;
        chosenParameters: Type[];
        arguments: Expression[];
    }
    export interface Literal {
        type: "literal";
        literalType: Type;
        value: string;
    }
    export interface ListLiteral {
        type: "list";
        chosenParameter: Type;
        contents: Expression[];
    }
    export interface Follow {
        type: "follow";
        // TODO: maybe rename
        expression: Expression;
        name: string;
    }
    export interface NamedFunctionBinding {
        type: "namedBinding";
        function: string;
        chosenParameters: Type[];
        bindings: Binding[];
    }
    export interface ExpressionFunctionBinding {
        type: "expressionBinding";
        expression: Expression;
        chosenParameters: Type[];
        bindings: Binding[];
    }
    export interface InlineFunction {
        type: "inlineFunction";
        arguments: Argument[];
        body: Block;
    }
}

export type Binding = Expression | null;

export type Type = "Integer"
 | "Natural"
 | "Boolean"
 | { List: Type }
 | { Try: Type }
 | Type.FunctionType
 | Type.NamedType;
export namespace Type {
    export interface FunctionType {
        from: Type[];
        to: Type;
    }
    export interface NamedType {
        name: string;
        params?: Type[];
    }
}

export function isTryType(type: Type): type is { Try: Type } {
    return typeof type === "object" && "Try" in type;
}

export function isNamedType(type: Type): type is Type.NamedType {
    return typeof type === "object" && "name" in type;
}

export function getAdapterStruct(interfac: Interface): Struct {
    const dataTypeName = getDataTypeName(interfac.typeParameters);
    const members = interfac.methods.map((method) => 
        ({
            name: method.name,
            type: addDataTypeArg(getFunctionType(method), dataTypeName),
        })
    );
    return {
        id: interfac.id + ".Adapter",
        members,
        typeParameters: [dataTypeName].concat(interfac.typeParameters || []),
    };
}

function addDataTypeArg(type: Type.FunctionType, dataTypeName: string): Type.FunctionType {
    return {
        from: [{name: dataTypeName} as Type].concat(type.from),
        to: type.to,
    };
}

function getFunctionType(method: Method): Type.FunctionType {
    return {
        from: method.arguments.map((argument) => argument.type),
        to: method.returnType,
    };
}

function getNamedType(id: string, params: Type[] | undefined): Type.NamedType {
    return {
        name: id,
        params
    }
}

export function getStructType(struct: Struct): Type.NamedType {
    return {
        name: struct.id,
        params: struct.typeParameters === undefined ? undefined :
                struct.typeParameters.map((paramName) => getNamedType(paramName, undefined)),
    }
}

function getDataTypeName(existingTypeParameters: string[] | undefined): string {
    if (existingTypeParameters === undefined) {
        return "A";
    }
    if (existingTypeParameters.indexOf("A") < 0) {
        return "A";
    }
    let index = 2
    while (true) {
        const name = "A" + index
        if (existingTypeParameters.indexOf(name) < 0) {
            return name
        }
        index++
    }
}
