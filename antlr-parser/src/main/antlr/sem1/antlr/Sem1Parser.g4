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
  UNION,
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
  TILDE,
  MODULE_ID,
  ID,
  UNDERSCORE
}

file : top_level_entities EOF ;

namespace : ID | ID DOT namespace ;
    catch[RecognitionException e] { throw e; }
entity_id : ID | namespace DOT ID ;
    catch[RecognitionException e] { throw e; }
entity_ref : entity_id | module_ref COLON entity_id ;
    catch[RecognitionException e] { throw e; }
type_ref : entity_id | TILDE entity_id | module_ref COLON entity_id | module_ref COLON TILDE entity_id ;
    catch[RecognitionException e] { throw e; }
module_ref : module_id // Name only
  | module_id COLON module_id // Group and name
  | module_id COLON module_id COLON LITERAL ; // Group, name, and version
    catch[RecognitionException e] { throw e; }
module_id : ID | MODULE_ID ;
    catch[RecognitionException e] { throw e; }

top_level_entities :
  | function top_level_entities
  | struct top_level_entities
  | interfac top_level_entities
  | union top_level_entities ;

function : annotations FUNCTION entity_id LPAREN function_arguments RPAREN COLON type block
         | annotations FUNCTION entity_id LESS_THAN cd_type_parameters GREATER_THAN LPAREN function_arguments RPAREN COLON type block ;
struct : annotations STRUCT optional_tilde entity_id LBRACE members maybe_requires RBRACE
  | annotations STRUCT optional_tilde entity_id LESS_THAN cd_type_parameters GREATER_THAN LBRACE members maybe_requires RBRACE ;
// TODO: Is there a better solution for this than mangling the name?
interfac : annotations INTERFACE entity_id LBRACE methods RBRACE
  | annotations INTERFACE entity_id LESS_THAN cd_type_parameters GREATER_THAN LBRACE methods RBRACE ;
union : annotations UNION entity_id LBRACE disjuncts RBRACE
  | annotations UNION entity_id LESS_THAN cd_type_parameters GREATER_THAN LBRACE disjuncts RBRACE ;

block : LBRACE assignments return_statement RBRACE ;
    catch[RecognitionException e] { throw e; }
function_arguments : | function_argument | function_argument COMMA function_arguments ;
    catch[RecognitionException e] { throw e; }
function_argument : ID COLON type ;
    catch[RecognitionException e] { throw e; }

optional_tilde : | TILDE ;
    catch[RecognitionException e] { throw e; }
members : | member members ;
    catch[RecognitionException e] { throw e; }
member : ID COLON type ;
    catch[RecognitionException e] { throw e; }
maybe_requires : | REQUIRES block ;
    catch[RecognitionException e] { throw e; }

methods : | method methods ;
    catch[RecognitionException e] { throw e; }
method : ID LPAREN function_arguments RPAREN COLON type
  | ID LESS_THAN cd_type_parameters GREATER_THAN LPAREN function_arguments RPAREN COLON type ;
    catch[RecognitionException e] { throw e; }

disjuncts: | disjunct disjuncts ;
    catch[RecognitionException e] { throw e; }
disjunct: ID COLON type | ID ;
    catch[RecognitionException e] { throw e; }

annotations : | annotation annotations ;
    catch[RecognitionException e] { throw e; }
annotation : annotation_name
  | annotation_name LPAREN annotation_contents_list RPAREN ;
    catch[RecognitionException e] { throw e; }
annotation_name : AT entity_id ;
    catch[RecognitionException e] { throw e; }
annotation_contents_list : | annotation_item | annotation_item COMMA | annotation_item COMMA annotation_contents_list ;
    catch[RecognitionException e] { throw e; }
annotation_item : LITERAL | LBRACKET annotation_contents_list RBRACKET ;
    catch[RecognitionException e] { throw e; }

// cd_type_parameters is nonempty
cd_type_parameters : type_parameter | type_parameter COMMA | type_parameter COMMA cd_type_parameters ;
    catch[RecognitionException e] { throw e; }
type_parameter : ID | TILDE ID | ID COLON type_class ;
    catch[RecognitionException e] { throw e; }
type_class : ID ;
    catch[RecognitionException e] { throw e; }

assignments : | assignment assignments ;
    catch[RecognitionException e] { throw e; }
assignment : LET ID ASSIGN expression
  | LET ID COLON type ASSIGN expression;
    catch[RecognitionException e] { throw e; }
return_statement: expression ;
    catch[RecognitionException e] { throw e; }

type : type_ref
  | type_ref LESS_THAN cd_types GREATER_THAN
  | LPAREN cd_types RPAREN ARROW type
  | LESS_THAN cd_type_parameters GREATER_THAN LPAREN cd_types RPAREN ARROW type ;
    catch[RecognitionException e] { throw e; }
cd_types : | type | type COMMA | type COMMA cd_types ;
    catch[RecognitionException e] { throw e; }
cd_types_nonempty : type | type COMMA | type COMMA cd_types_nonempty ;
    catch[RecognitionException e] { throw e; }
cd_types_or_underscores_nonempty : type_or_underscore | type_or_underscore COMMA | type_or_underscore COMMA cd_types_or_underscores_nonempty ;
    catch[RecognitionException e] { throw e; }
type_or_underscore : UNDERSCORE | type ;
    catch[RecognitionException e] { throw e; }
expression : IF LPAREN expression RPAREN block ELSE block
  | type_ref DOT LITERAL
  | LBRACKET cd_expressions RBRACKET LESS_THAN type GREATER_THAN
  | expression ARROW ID
  | expression PIPE LPAREN cd_expressions_or_underscores RPAREN // Function binding
  | entity_ref PIPE LPAREN cd_expressions_or_underscores RPAREN // Function binding
  | expression LESS_THAN cd_types_or_underscores_nonempty GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function binding with type parameters
  | entity_ref LESS_THAN cd_types_or_underscores_nonempty GREATER_THAN PIPE LPAREN cd_expressions_or_underscores RPAREN // Function binding with type parameters
  | expression LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | entity_ref LPAREN cd_expressions RPAREN // Calling function reference OR function variable
  | expression LESS_THAN cd_types_nonempty GREATER_THAN LPAREN cd_expressions RPAREN
  | entity_ref LESS_THAN cd_types_nonempty GREATER_THAN LPAREN cd_expressions RPAREN
  | FUNCTION LPAREN function_arguments RPAREN COLON type block
  | ID // Variable
  ;
    catch[RecognitionException e] { throw e; }

// cd_expressions may be empty
cd_expressions : | expression | expression COMMA cd_expressions ;
    catch[RecognitionException e] { throw e; }
cd_expressions_or_underscores : | expression_or_underscore | expression_or_underscore COMMA cd_expressions_or_underscores ;
    catch[RecognitionException e] { throw e; }
expression_or_underscore : UNDERSCORE | expression ;
    catch[RecognitionException e] { throw e; }
