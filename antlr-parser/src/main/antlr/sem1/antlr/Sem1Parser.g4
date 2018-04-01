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
  REQUIRES,
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
  HYPHEN,
  TILDE,
  ID,
  UNDERSCORE
}

file : top_level_entities EOF ;

namespace : ID | ID DOT namespace ;
entity_id : ID | namespace DOT ID ;
entity_ref : entity_id | module_ref COLON entity_id ;
type_ref : entity_id | TILDE entity_id | module_ref COLON entity_id | module_ref COLON TILDE entity_id ;
module_ref : module_id // Name only
  | module_id COLON module_id // Group and name
  | module_id COLON module_id COLON module_id ; // Group, name, and version
module_id : ID | ID HYPHEN module_id | ID UNDERSCORE module_id | ID DOT module_id ;

top_level_entities : | function top_level_entities | struct top_level_entities | interfac top_level_entities ;

function : annotations FUNCTION entity_id LPAREN function_arguments RPAREN COLON type block
         | annotations FUNCTION entity_id LESS_THAN cd_ids GREATER_THAN LPAREN function_arguments RPAREN COLON type block ;
block : LBRACE assignments return_statement RBRACE ;
function_arguments : | function_argument | function_argument COMMA function_arguments ;
function_argument : ID COLON type ;

struct : annotations STRUCT entity_id LBRACE struct_members maybe_requires RBRACE
  | annotations STRUCT entity_id LESS_THAN cd_ids GREATER_THAN LBRACE struct_members maybe_requires RBRACE ;
struct_members : | struct_member struct_members ;
struct_member : ID COLON type ;
maybe_requires : | REQUIRES block ;

// TODO: Is there a better solution for this than mangling the name?
interfac : annotations INTERFACE entity_id LBRACE methods RBRACE
  | annotations INTERFACE entity_id LESS_THAN cd_ids GREATER_THAN LBRACE methods RBRACE ;
methods : | method methods ;
method : ID LPAREN function_arguments RPAREN COLON type
  | ID LESS_THAN cd_ids GREATER_THAN LPAREN function_arguments RPAREN COLON type ;

annotations : | annotation annotations ;
annotation : annotation_name
  | annotation_name LPAREN annotation_contents_list RPAREN ;
annotation_name : AT entity_id ;
annotation_contents_list : | annotation_item | annotation_item COMMA | annotation_item COMMA annotation_contents_list ;
annotation_item : LITERAL | LBRACKET annotation_contents_list RBRACKET ;

// cd_ids is nonempty
cd_ids : ID | ID COMMA | ID COMMA cd_ids ;

assignments : | assignment assignments ;
assignment : LET ID ASSIGN expression
  | LET ID COLON type ASSIGN expression;
return_statement: expression ;

type : type_ref
  | type_ref LESS_THAN cd_types GREATER_THAN
  | LPAREN cd_types RPAREN ARROW type ;
cd_types : | type | type COMMA | type COMMA cd_types ;
cd_types_nonempty : type | type COMMA | type COMMA cd_types_nonempty ;
expression : IF LPAREN expression RPAREN block ELSE block
  | type_ref DOT LITERAL
  | LBRACKET cd_expressions RBRACKET LESS_THAN type GREATER_THAN
  | expression ARROW ID
  | expression PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference
  | entity_ref PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference
  | expression LESS_THAN cd_types_nonempty GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference with type parameters
  | entity_ref LESS_THAN cd_types_nonempty GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function reference with type parameters
  | expression LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | entity_ref LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | expression LESS_THAN cd_types_nonempty GREATER_THAN LPAREN cd_expressions RPAREN
  | entity_ref LESS_THAN cd_types_nonempty GREATER_THAN LPAREN cd_expressions RPAREN
  | FUNCTION LPAREN function_arguments RPAREN COLON type block
  | ID // Variable
  ;

// cd_expressions may be empty
cd_expressions : | expression | expression COMMA cd_expressions ;
cd_expressions_or_underscores : | expression_or_underscore | expression_or_underscore COMMA cd_expressions_or_underscores ;
expression_or_underscore : UNDERSCORE | expression ;
