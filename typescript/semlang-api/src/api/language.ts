
// Note: This is the same type as the Kotlin toJson outputs
export interface Context {
    semlang: string; // Language variant
    version: string; // Language/format version
    functions: Function[];
    structs: Struct[];
    unions: Union[];
}

// TODO: Support upstream modules
export interface Module {
    functions: { [id: string]: Function }
    structs: { [id: string]: Struct }
    unions: { [id: string]: Union }
    unionsByWhenId: { [whenId: string]: Union }
    unionsByOptionId: { [optionId: string]: [Union, number] }
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
    isThreaded?: boolean;
    typeParameters?: string[];
    annotations?: Annotation[];
    members: Member[];
    requires?: Block;
}

export interface Member {
    name: string;
    type: Type;
}

export interface Union {
    id: string;
    annotations?: Annotation[];
    typeParameters?: string[];
    options: Option[];
}

export interface Option {
    name: string;
    type?: Type;
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
export type BlockElement = Statement | { return: Expression };

export function isStatement(blockElement: BlockElement): blockElement is Statement {
    return "be" in blockElement;
}

export interface Statement {
    let?: string;
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
 | { List: Type }
 | { Maybe: Type }
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

export function isListType(type: Type): type is { List: Type } {
    return typeof type === "object" && "List" in type;
}

export function isMaybeType(type: Type): type is { Maybe: Type } {
    return typeof type === "object" && "Maybe" in type;
}

export function isNamedType(type: Type): type is Type.NamedType {
    return typeof type === "object" && "name" in type;
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
