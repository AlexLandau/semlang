import { statement } from "@babel/template";

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

export type Block = Statement[];

export interface Annotation {
    name: string;
    values: AnnotationArgument[];
}

// The following is disallowed, so I need to cheat a little...
// export type AnnotationArgument = string | AnnotationArgument[];
export type AnnotationArgument = string | (string | any[])[];

export type Statement = 
 | Statement.Assignment
 | Statement.Bare
 | Statement.Return
 ;
export namespace Statement {
    export interface Assignment {
        let: string;
        be: Expression;
    }
    export interface Bare {
        do: Expression;
    }
    export interface Return {
        return: Expression;
    }
}

export function isAssignment(statement: Statement): statement is Statement.Assignment {
    return "let" in statement;
}
export function isBareStatement(statement: Statement): statement is Statement.Bare {
    return "do" in statement;
}
export function isReturnStatement(statement: Statement): statement is Statement.Return {
    return "return" in statement;
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

export type Type =
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
