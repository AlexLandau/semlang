import { isEqual } from "lodash";
import { Module } from "../api/language";
import { interpret, evaluateLiteral } from "./interpret";
import { SemObject } from "./SemObject";


// Returns the error messages from any failed tests.
export function runTests(module: Module): string[] {
    const errorMessages = [] as string[];
    for (const functionName in module.functions) {
        const fn = module.functions[functionName];
        if (fn.annotations !== undefined) {
            for (const annotation of fn.annotations) {
                if (annotation.name === "Test") {
                    const values = annotation.values;
                    if (values.length !== 2) {
                        throw new Error(`@Test annotations should have two arguments`);
                    }
                    const argLiterals = values[0] as string[];
                    const outputLiteral = values[1] as string;

                    const argObjects = [] as SemObject[];
                    for (let i = 0; i < argLiterals.length; i++) {
                        const type = fn.arguments[i].type;
                        const literal = argLiterals[i];
                        argObjects.push(evaluateLiteral(module, type, literal));
                    }
                    const expectedOutput = evaluateLiteral(module, fn.returnType, outputLiteral);
                    
                    const actualOutput = interpret(module, functionName, argObjects);

                    const outputIsCorrect = isEqual(expectedOutput, actualOutput);
                    if (!outputIsCorrect) {
                        errorMessages.push(`Expected ${functionName}(${argLiterals}) to be ${outputLiteral}, but was ${JSON.stringify(actualOutput)}`);
                    }
                }
            }
        }
    }
    return errorMessages;
}
