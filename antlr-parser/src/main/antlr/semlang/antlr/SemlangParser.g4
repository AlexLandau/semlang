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
  LITERAL,
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
  ARROW,
  LESS_THAN,
  GREATER_THAN,
  PIPE,
  ID,
  UNDERSCORE
}

file : functions_or_structs EOF ;

packag : ID | ID DOT packag ; // Antlr doesn't like the word "package"
function_id : ID | packag DOT ID ;

functions_or_structs : | function functions_or_structs | struct functions_or_structs ;
function : FUNCTION function_id LPAREN function_arguments RPAREN COLON type block
         | FUNCTION LESS_THAN cd_ids GREATER_THAN function_id LPAREN function_arguments RPAREN COLON type block ;
block : LBRACE assignments return_statement RBRACE ;
function_arguments : | function_argument | function_argument COMMA function_arguments ;
function_argument : ID COLON type ;

struct : STRUCT function_id LBRACE struct_components RBRACE
  | STRUCT function_id LESS_THAN cd_ids GREATER_THAN LBRACE struct_components RBRACE ;
struct_components : | struct_component struct_components ;
struct_component : ID COLON type ;

// cd_ids is nonempty
cd_ids : ID | ID COMMA cd_ids ;

assignments : | assignment | assignment assignments ;
assignment : LET ID COLON type ASSIGN expression ;
return_statement: RETURN expression ;

type : simple_type_id
  | simple_type_id LESS_THAN cd_types GREATER_THAN
  | LPAREN cd_types RPAREN ARROW type ;
// cd_types is nonempty
cd_types : type | type COMMA cd_types ;
// A "simple type ID" has no type parameters
simple_type_id : ID | packag DOT ID ;
expression : IF LPAREN expression RPAREN block ELSE block
  | simple_type_id DOT LITERAL
  | expression ARROW ID
  | function_id PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference
  | function_id LESS_THAN cd_types GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference with type parameters
  | function_id LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | function_id LESS_THAN cd_types GREATER_THAN LPAREN cd_expressions RPAREN
  | ID // Variable
  ;
// cd_expressions may be empty
cd_expressions : | expression | expression COMMA cd_expressions ;
cd_expressions_or_underscores : | expression
  | UNDERSCORE
  | expression COMMA cd_expressions_or_underscores
  | UNDERSCORE COMMA cd_expressions_or_underscores ;
