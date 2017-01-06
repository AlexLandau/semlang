lexer grammar SemlangLexer;

@header {
  package semlang.antlr;
}

// Whitespace
NEWLINE            : ('\r\n' | 'r' | '\n') -> skip ;
WS                 : [\t ]+ -> skip ;

// Keywords
PACKAGE            : 'package';
IMPORT             : 'import';
FUNCTION           : 'function';
STRUCT             : 'struct';
RETURN             : 'return';
LET                : 'let';
IF                 : 'if';
ELSE               : 'else';

// Literals
LITERAL            : '"' ( ~["\\] | '\\' . )* '"' ;

// Operators
DOT                : '.' ;
COMMA              : ',' ;
COLON              : ':' ;
ASSIGN             : '=' ;
EQUALS             : '==' ;
LPAREN             : '(' ;
RPAREN             : ')' ;
LBRACKET           : '[' ;
RBRACKET           : ']' ;
LBRACE             : '{' ;
RBRACE             : '}' ;
D_QUOTE            : '"' ;
S_QUOTE            : '\'' ;
ARROW              : '->' ;
LESS_THAN          : '<' ;
GREATER_THAN       : '>' ;

// Identifiers
ID                 : [_]*[a-zA-Z0-9][A-Za-z0-9_]* ;