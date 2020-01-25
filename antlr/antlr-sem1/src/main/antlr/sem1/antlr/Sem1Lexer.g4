lexer grammar Sem1Lexer;

@header {
  package sem1.antlr;
}

// Whitespace
// Note: The whitespace sensitivity here is for the benefit of sem2. It preserves the property
// that valid sem1 can be parsed as sem2 with the same meaning.
// Left parentheses are special, and need to know if they had preceding whitespace
LPAREN_AFTER_WS    : [\t\r\n ]+ '(' ;
// Also for less-than symbols
// This is a fix around foo<bar>(baz) getting parsed as a greater-than expression; see https://github.com/AlexLandau/antlr-preferring-later-rule
LESS_THAN_AFTER_WS : [\t\r\n ]+ '<' ;
NEWLINE            : ('\r\n' | 'r' | '\n') -> skip ;
WS                 : [\t ]+ -> skip ;

// Comments
LINE_COMMENT       : '//' (~[\n\r])* (NEWLINE | EOF) -> skip ;
BLOCK_COMMENT       : '/*' .*? '*/' -> skip;

// Keywords
PACKAGE            : 'package';
IMPORT             : 'import';
FUNCTION           : 'function';
STRUCT             : 'struct';
UNION              : 'union';
RETURN             : 'return';
LET                : 'let';
IF                 : 'if';
ELSE               : 'else';
REQUIRES           : 'requires';

// Literals
LITERAL            : '"' ( ~["\\\n\r] | '\\' ~[\n\r] )* '"' ;

// Operators
DOT                : '.' ;
COMMA              : ',' ;
COLON              : ':' ;
ASSIGN             : '=' ;
EQUALS             : '==' ;
LPAREN_NO_WS       : '(' ;
RPAREN             : ')' ;
LBRACKET           : '[' ;
RBRACKET           : ']' ;
LBRACE             : '{' ;
RBRACE             : '}' ;
D_QUOTE            : '"' ;
S_QUOTE            : '\'' ;
ARROW              : '->' ;
LESS_THAN_NO_WS    : '<' ;
GREATER_THAN       : '>' ;
PIPE               : '|' ;
AT                 : '@' ;
AMPERSAND          : '&' ;

// Identifiers
MODULE_ID          : ([a-zA-Z]|[_]+[a-zA-Z0-9])[A-Za-z0-9_]*('-'[A-Za-z0-9]+)+ ;
ID                 : ([a-zA-Z]|[_]+[a-zA-Z0-9])[A-Za-z0-9_]* ;
UNDERSCORE         : '_' ;
