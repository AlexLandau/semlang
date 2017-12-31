
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
    value?: string;
}

// TODO: Maybe reconsider these two and Block?
export type BlockElement = Assignment | { return: Expression };

export interface Assignment {
    let: string;
    "=": Expression;
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

// TODO: This should probably have undefined in place of {}
export type Binding = Expression | {};


