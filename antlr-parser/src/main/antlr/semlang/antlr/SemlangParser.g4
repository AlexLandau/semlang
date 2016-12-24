parser grammar SemlangParser;

@header {
  package semlang.antlr;
}

tokens {
  NEWLINE,
  WS,
  PACKAGE,
  IMPORT,
  FUNCTION,
  STRUCT,
  RETURN,
  LET,
  IF,
  ELSE,
  INTLIT,
  DECLIT,
  DOT,
  COMMA,
  COLON,
  ASSIGN,
  EQUALS,
  LPAREN,
  RPAREN,
  LBRACKET,
  RBRACKET,
  LBRACE,
  RBRACE,
  D_QUOTE,
  S_QUOTE,
  ID
}

file : functions EOF ;

functions : | function | function functions ;
function : FUNCTION function_id LPAREN function_arguments RPAREN COLON type block ;
function_id : ID | packag DOT ID ;
packag : ID | ID DOT packag ; // Antlr doesn't like the word "package"
block : LBRACE assignments return_statement RBRACE ;
function_arguments : | function_argument | function_argument COMMA function_arguments ;
function_argument : ID COLON type ;

assignments : | assignment | assignment assignments ;
assignment : LET ID COLON type ASSIGN expression ;
return_statement: RETURN expression ;

type : ID ;
expression : ID | expression EQUALS expression | function_id LPAREN cd_expressions RPAREN ;
cd_expressions : | expression | expression COMMA cd_expressions ;
