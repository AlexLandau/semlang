parser grammar Sem1Parser;

@header {
  package sem1.antlr;
}

tokens {
  NEWLINE,
  WS,
  LINE_COMMENT,
  BLOCK_COMMENT,
  PACKAGE,
  IMPORT,
  FUNCTION,
  STRUCT,
  INTERFACE,
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
  AT,
  ID,
  UNDERSCORE
}

file : top_level_entities EOF ;

packag : ID | ID DOT packag ; // Antlr doesn't like the word "package"
function_id : ID | packag DOT ID ;

top_level_entities : | function top_level_entities | struct top_level_entities | interfac top_level_entities ;

function : annotations FUNCTION function_id LPAREN function_arguments RPAREN COLON type block
         | annotations FUNCTION function_id LESS_THAN cd_ids GREATER_THAN LPAREN function_arguments RPAREN COLON type block ;
block : LBRACE assignments return_statement RBRACE ;
function_arguments : | function_argument | function_argument COMMA function_arguments ;
function_argument : ID COLON type ;

struct : annotations STRUCT function_id LBRACE struct_components RBRACE
  | annotations STRUCT function_id LESS_THAN cd_ids GREATER_THAN LBRACE struct_components RBRACE ;
struct_components : | struct_component struct_components ;
struct_component : ID COLON type ;

// TODO: Is there a better solution for this than mangling the name?
interfac : annotations INTERFACE function_id LBRACE methods RBRACE
  | annotations INTERFACE function_id LESS_THAN cd_ids GREATER_THAN LBRACE methods RBRACE ;
methods : | method methods ;
method : ID LPAREN function_arguments RPAREN COLON type
  | ID LESS_THAN cd_ids GREATER_THAN LPAREN function_arguments RPAREN COLON type ;

annotations : | annotation annotations ;
annotation : annotation_name
  | annotation_name LPAREN LITERAL RPAREN ;
annotation_name : AT ID ;

// cd_ids is nonempty
cd_ids : ID | ID COMMA | ID COMMA cd_ids ;

assignments : | assignment assignments ;
assignment : LET ID ASSIGN expression
  | LET ID COLON type ASSIGN expression;
return_statement: expression ;

type : simple_type_id
  | simple_type_id LESS_THAN cd_types GREATER_THAN
  | LPAREN cd_types RPAREN ARROW type ;
// cd_types is nonempty
cd_types : type | type COMMA | type COMMA cd_types ;
// A "simple type ID" has no type parameters
simple_type_id : ID | packag DOT ID ;
expression : IF LPAREN expression RPAREN block ELSE block
  | simple_type_id DOT LITERAL
  | expression ARROW ID
  | expression PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference
  | function_id PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference
  | expression LESS_THAN cd_types GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference with type parameters
  | function_id LESS_THAN cd_types GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference with type parameters
  | expression LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | function_id LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | expression LESS_THAN cd_types GREATER_THAN LPAREN cd_expressions RPAREN
  | function_id LESS_THAN cd_types GREATER_THAN LPAREN cd_expressions RPAREN
  | ID // Variable
  ;

// cd_expressions may be empty
cd_expressions : | expression | expression COMMA cd_expressions ;
cd_expressions_or_underscores : | expression_or_underscore | expression_or_underscore COMMA cd_expressions_or_underscores ;
expression_or_underscore : UNDERSCORE | expression ;
