
// TODO: Someday, we'll have this parser written in Semlang and transpiled into languages
// to use as they need it. Until then, just copy and port the Kotlin code...

export type ComplexLiteralNode = string | { square: ComplexLiteralNode[] } | { curly: CurlyNode[] } | { angle: ComplexLiteralNode[] };
export function isLiteralNode(node: ComplexLiteralNode): node is string {
    return typeof node === "string";
}
export function isSquareListNode(node: ComplexLiteralNode): node is { square: ComplexLiteralNode[] } {
    return typeof node === "object" && "square" in node;
}

export type CurlyNode = ComplexLiteralNode | [string, ComplexLiteralNode];

type ParserState = ParserState.Initial | ParserState.SquareList | ParserState.Literal | "InBlockComment" | "InLineComment";
namespace ParserState {
    export type Initial = { contents: ComplexLiteralNode[] };
    export function Initial(): Initial {
        return { contents: [] };
    }
    export type SquareList = { square: ComplexLiteralNode[], justHadComma: boolean };
    export function SquareList(): SquareList {
        return { square: [], justHadComma: true };
    }
    export type Literal = { literal: string[], justHadBackslash: boolean };
    export function Literal(): Literal {
        return { literal: [], justHadBackslash: false };
    }
}

function isInitial(state: ParserState): state is ParserState.Initial {
    return typeof state === "object" && "contents" in state;
}

function isSquareList(state: ParserState): state is ParserState.SquareList {
    return typeof state === "object" && "square" in state;
}

function isLiteral(state: ParserState): state is ParserState.Literal {
    return typeof state === "object" && "literal" in state;
}

// This treats the array like a stack, to go with push() and pop()
function peek<T>(list: Array<T>): T {
    const lastIndex = list.length - 1;
    return list[lastIndex];
}

export function parseComplexLiteral(input: String) {
    let i = 0;
    const stateStack = [] as ParserState[];

    function addNodeToState(state: ParserState, nodeToAdd: ComplexLiteralNode) {
        if (isInitial(state)) {
            state.contents.push(nodeToAdd);
        } else if (isSquareList(state)) {
            state.square.push(nodeToAdd);
        } else {
            throw new Error("Bad state to add things to: " + state);
        }
    }
    
    function handleForwardSlashPossiblyStartingComment() {
        if (i + 1 >= input.length) {
            throw new Error("Extra '/' at end of complex literal")
        }
        const nextChar = input[i + 1]
        if (nextChar === '/') {
            stateStack.push("InLineComment")
            i++
        } else if (nextChar === '*') {
            stateStack.push("InBlockComment")
            i++
        } else {
            throw new Error("Unexpected '/' not part of comment declaration");
        }
    }

    stateStack.push(ParserState.Initial())
    while (i < input.length) {
        const curChar = input[i]

        const curState = peek(stateStack)

        if (isInitial(curState)) {
            if (curChar === '/') {
                handleForwardSlashPossiblyStartingComment();
            } else if (curChar === '[') {
                stateStack.push(ParserState.SquareList())
            } else if (curChar === ' ' || curChar === '\t' || curChar === '\n' || curChar === '\r') {
                // Ignore whitespace
            } else {
                throw new Error("Unexpected character $curChar");
            }
        } else if (isSquareList(curState)) {
            if (curChar === '/') {
                handleForwardSlashPossiblyStartingComment()
            } else if (curChar === '[') {
                if (!curState.justHadComma) {
                    throw new Error("Was expecting a comma or end-of-list before the next literal")
                }
                curState.justHadComma = false
                stateStack.push(ParserState.SquareList())
            } else if (curChar === ']') {
                const completedState = curState
                stateStack.pop()
                const completedListNode = { square: completedState.square };
                addNodeToState(peek(stateStack), completedListNode);
            } else if (curChar === '\'') {
                if (!curState.justHadComma) {
                    throw new Error("Was expecting a comma or end-of-list before the next literal")
                }
                curState.justHadComma = false
                stateStack.push(ParserState.Literal())
            } else if (curChar === ',') {
                if (curState.justHadComma) {
                    throw new Error("Unexpected comma")
                }
                curState.justHadComma = true
            } else if (curChar === ' ' || curChar === '\t' || curChar === '\n' || curChar === '\r') {
                // Ignore whitespace
            } else {
                throw new Error("Unexpected character $curChar");
            }
        } else if (isLiteral(curState)) {
            if (curState.justHadBackslash) {
                curState.literal.push(applyBackslashRules(curChar))
                curState.justHadBackslash = false
            } else {
                if (curChar === '\'') {
                    const completedState = curState
                    stateStack.pop()
                    const completedLiteralNode = completedState.literal.join("");
                    addNodeToState(peek(stateStack), completedLiteralNode)
                } else if (curChar === '\\') {
                    curState.justHadBackslash = true
                } else {
                    curState.literal.push(curChar);
                }
            }
        } else if (curState === "InBlockComment") {
            if (curChar == '*') {
                if (i + 1 < input.length && input[i + 1] == '/') {
                    stateStack.pop() // Leave the comment
                    i++
                }
            }
        } else if (curState === "InLineComment") {
            if (curChar == '\n') {
                stateStack.pop()
            }
        } else {
            throw new Error(`Unexpected current state type`);
        }

        i++
    }
    if (stateStack.length != 1) {
        throw new Error("Complex literal ends abruptly");
    }
    const endState = stateStack.pop()
    if (endState == null || !isInitial(endState)) {
        throw new Error("Ended with non-initial state");
    }
    const nodes = endState.contents
    if (nodes.length === 0) {
        throw new Error("Complex literal had no content")
    } else if (nodes.length > 1) {
        throw new Error("Complex literal contained multiple values outside of a list or structure")
    }
    return nodes[0];
}

function applyBackslashRules(curChar: string): string {
    // TODO: Find the other function that already does this for strings and use that
    if (curChar in ['n', 't', 'r']) {
        throw new Error("TODO");
    }
    return curChar
}
