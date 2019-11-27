import { isEqual } from "lodash";
import { Module, Type, isMaybeType, isNamedType } from "../api/language";
import { interpret, evaluateLiteral, InterpreterContext } from "./interpret";
import { SemObject, listObject, failureObject, successObject } from "./SemObject";
import { isOpaqueType } from "@babel/types";


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
                    const argLiterals = values[0] as (string | string[])[];
                    const outputLiteral = values[1] as string | string[];

                    const argObjects = [] as SemObject[];
                    for (let i = 0; i < argLiterals.length; i++) {
                        const type = fn.arguments[i].type;
                        const literal = argLiterals[i];
                        argObjects.push(evaluateAnnotationLiteral(module, type, literal));
                    }
                    const expectedOutput = evaluateAnnotationLiteral(module, fn.returnType, outputLiteral);
                    
                    const actualOutput = interpret(module, functionName, argObjects);

                    const outputIsCorrect = isEqual(expectedOutput, actualOutput);
                    if (!outputIsCorrect) {
                        errorMessages.push(`Expected ${functionName}(${argLiterals}) to be ${JSON.stringify(expectedOutput)}, but was ${JSON.stringify(actualOutput)}`);
                    }
                }
            }
        }
    }
    return errorMessages;
}

function evaluateAnnotationLiteral(module: Module, type: Type, annotationArg: string | string[]): SemObject {
    if (typeof annotationArg === "string") {
        return evaluateLiteral(module, type, annotationArg);
    }

    if (isMaybeType(type)) {
        const semObjects = annotationArg.map((value) => evaluateAnnotationLiteral(module, type.Maybe, value));
        if (semObjects.length === 0) {
            return failureObject();
        } else if (semObjects.length === 1) {
            return successObject(semObjects[0]);
        } else {
            throw new Error(`Expected a @Test annotation argument for a Maybe type (${JSON.stringify(type)}) to have zero or one item, but was: ${JSON.stringify(annotationArg)}`);
        }
    } else if (isNamedType(type) && type.name === "List") {
        const semObjects = annotationArg.map((value) => evaluateAnnotationLiteral(module, type.params![0], value));
        return listObject(semObjects);
    } else {
        throw new Error(`Expected a @Test annotation argument that was a list (${JSON.stringify(annotationArg)}) to be of a List or Maybe type, but was: ${JSON.stringify(type)}`);
    }
}
