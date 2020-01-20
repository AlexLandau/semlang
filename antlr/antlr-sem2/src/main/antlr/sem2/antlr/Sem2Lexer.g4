lexer grammar Sem2Lexer;

@header {
  package sem2.antlr;
}

// Whitespace
// Left parentheses are special, and need to know if they had preceding whitespace
LPAREN_AFTER_WS    : [\t\r\n ]+ '(' ;
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
WHILE              : 'while';

// Literals
LITERAL            : '"' ( ~["\\\n\r] | '\\' ~[\n\r] )* '"' ;
INTEGER_LITERAL    : [0-9]+ | '-' [0-9]+ ;

// Operators
DOT                : '.' ;
COMMA              : ',' ;
COLON              : ':' ;
ASSIGN             : '=' ;
DOT_ASSIGN         : '.=' ;
EQUALS             : '==' ;
NOT_EQUALS         : '!=' ;
LPAREN_NO_WS       : '(' ;
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
PIPE               : '|' ;
AT                 : '@' ;
AMPERSAND          : '&' ;
PLUS               : '+' ;
HYPHEN             : '-' ;
TIMES              : '*' ;
AND                : '&&' ;
OR                 : '||' ;

// Identifiers
MODULE_ID          : ([a-zA-Z]|[_]+[a-zA-Z0-9])[A-Za-z0-9_]*('-'[A-Za-z0-9]+)+ ;
ID                 : ([a-zA-Z]|[_]+[a-zA-Z0-9])[A-Za-z0-9_]* ;
UNDERSCORE         : '_' ;
