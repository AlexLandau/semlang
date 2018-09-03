lexer grammar Sem1Lexer;

@header {
  package sem1.antlr;
}

// Whitespace
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
INTERFACE          : 'interface';
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
PIPE               : '|' ;
AT                 : '@' ;
HYPHEN             : '-' ;
TILDE              : '~' ;

// Identifiers
ID                 : ([a-zA-Z]|[_]+[a-zA-Z0-9])[A-Za-z0-9_]* ;
UNDERSCORE         : '_' ;
