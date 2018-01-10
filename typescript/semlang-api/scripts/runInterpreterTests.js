#!/usr/bin/env node

const process = require("process");
const fs = require("fs");
const semlang = require("../lib/index");

const jsonFilename = process.argv[2]; // The first user argument to this script
const jsonString = fs.readFileSync(jsonFilename, 'utf8');
console.log("The contents of the file are: " + jsonString);
const context = JSON.parse(jsonString);
// Unfortunately, "module" is already a thing...
const theModule = semlang.toModule(context);

// TODO: Maybe also check for case where there are no tests found?
const errorMessages = semlang.runTests(theModule);
if (errorMessages.length > 0) {
    console.log("Errors were found:");
    errorMessages.forEach((message) => {
        console.log(message);
    });
    process.exit(1);
}

process.exit(0);
