parser grammar Sem2Parser;

@header {
  package sem2.antlr;
}

tokens {
  LPAREN_AFTER_WS,
  LESS_THAN_AFTER_WS,
  NEWLINE,
  WS,
  LINE_COMMENT,
  BLOCK_COMMENT,
  PACKAGE,
  IMPORT,
  FUNCTION,
  STRUCT,
  UNION,
  RETURN,
  LET,
  IF,
  ELSE,
  REQUIRES,
  WHILE,
  LITERAL,
  INTEGER_LITERAL,
  DOT,
  COMMA,
  COLON,
  ASSIGN,
  DOT_ASSIGN,
  EQUALS,
  NOT_EQUALS,
  LPAREN_NO_WS,
  RPAREN,
  LBRACKET,
  RBRACKET,
  LBRACE,
  RBRACE,
  D_QUOTE,
  S_QUOTE,
  ARROW,
  LESS_THAN_NO_WS,
  GREATER_THAN,
  PIPE,
  AT,
  AMPERSAND,
  PLUS,
  HYPHEN,
  TIMES,
  AND,
  OR,
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
type_ref : entity_id | AMPERSAND entity_id | module_ref COLON entity_id | module_ref COLON AMPERSAND entity_id ;
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
  | union top_level_entities ;

function : annotations FUNCTION entity_id lparen function_arguments RPAREN COLON type block
         | annotations FUNCTION entity_id less_than cd_type_parameters GREATER_THAN lparen function_arguments RPAREN COLON type block ;
struct : annotations STRUCT entity_id LBRACE members maybe_requires RBRACE
  | annotations STRUCT entity_id less_than cd_type_parameters GREATER_THAN LBRACE members maybe_requires RBRACE ;
union : annotations UNION entity_id LBRACE disjuncts RBRACE
  | annotations UNION entity_id less_than cd_type_parameters GREATER_THAN LBRACE disjuncts RBRACE ;

block : LBRACE statements RBRACE ;
    catch[RecognitionException e] { throw e; }
function_arguments : | function_argument | function_argument COMMA function_arguments ;
    catch[RecognitionException e] { throw e; }
function_argument : ID COLON type ;
    catch[RecognitionException e] { throw e; }
function_arguments_nonempty : function_argument | function_argument COMMA function_arguments_nonempty ;
    catch[RecognitionException e] { throw e; }

members : | member members ;
    catch[RecognitionException e] { throw e; }
member : ID COLON type ;
    catch[RecognitionException e] { throw e; }
maybe_requires : | REQUIRES block ;
    catch[RecognitionException e] { throw e; }

disjuncts: | disjunct disjuncts ;
    catch[RecognitionException e] { throw e; }
disjunct: ID COLON type | ID ;
    catch[RecognitionException e] { throw e; }

annotations : | annotation annotations ;
    catch[RecognitionException e] { throw e; }
annotation : annotation_name
  | annotation_name lparen annotation_contents_list RPAREN ;
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
type_parameter : ID | ID COLON type_class ;
    catch[RecognitionException e] { throw e; }
type_class : ID ;
    catch[RecognitionException e] { throw e; }

statements : | statement statements ;
    catch[RecognitionException e] { throw e; }
statement : assignment
  | expression
  | WHILE lparen expression RPAREN block;
    catch[RecognitionException e] { throw e; }

assignments : | assignment assignments ;
    catch[RecognitionException e] { throw e; }
assignment : LET ID ASSIGN expression
  | LET ID COLON type ASSIGN expression;
    catch[RecognitionException e] { throw e; }

type : type_ref
  | type_ref less_than cd_types GREATER_THAN
  | lparen cd_types RPAREN ARROW type
  | AMPERSAND lparen cd_types RPAREN ARROW type
  | less_than cd_type_parameters GREATER_THAN lparen cd_types RPAREN ARROW type
  | AMPERSAND less_than cd_type_parameters GREATER_THAN lparen cd_types RPAREN ARROW type ;
    catch[RecognitionException e] { throw e; }
cd_types : | type | type COMMA | type COMMA cd_types ;
    catch[RecognitionException e] { throw e; }
cd_types_nonempty : type | type COMMA | type COMMA cd_types_nonempty ;
    catch[RecognitionException e] { throw e; }
cd_types_or_underscores_nonempty : type_or_underscore | type_or_underscore COMMA | type_or_underscore COMMA cd_types_or_underscores_nonempty ;
    catch[RecognitionException e] { throw e; }
type_or_underscore : UNDERSCORE | type ;
    catch[RecognitionException e] { throw e; }
expression : IF lparen expression RPAREN block ELSE block
  | type_ref DOT LITERAL // sem1-style literal with explicit type, e.g. String."foo" or Integer."42"
  | ID
  | LITERAL // String literal, e.g. "foo"
  | INTEGER_LITERAL // Integer literal, e.g. 42
  | LBRACKET cd_expressions RBRACKET less_than type GREATER_THAN
  | expression LESS_THAN_NO_WS cd_types_or_underscores_nonempty GREATER_THAN PIPE LPAREN_NO_WS cd_expressions_or_underscores RPAREN // Function binding with type parameters
  | expression LESS_THAN_NO_WS cd_types_nonempty GREATER_THAN LPAREN_NO_WS cd_expressions RPAREN
  | expression ARROW ID
  | expression PIPE LPAREN_NO_WS cd_expressions_or_underscores RPAREN // Function binding
  | expression LPAREN_NO_WS cd_expressions RPAREN // Calling function reference OR function variable
  | FUNCTION lparen function_arguments RPAREN COLON type block
  | FUNCTION lparen function_arguments RPAREN block
  | LBRACE optional_args statements RBRACE
  | expression DOT ID
  // NOTE: Higher expressions have higher precedence
  | expression LBRACKET cd_expressions RBRACKET // [] (get) operator
  | expression TIMES expression // * operator
  | expression PLUS expression // + operator
  | expression HYPHEN expression // - operator
  | expression LESS_THAN_AFTER_WS expression // < operator
  | expression GREATER_THAN expression // > operator
  | expression EQUALS expression // == operator
  | expression NOT_EQUALS expression // != operator
  | expression DOT_ASSIGN expression // .= operator
  | expression AND expression // && operator
  | expression OR expression // || operator
  | lparen expression RPAREN
  ;
    catch[RecognitionException e] { throw e; }

optional_args : | function_arguments_nonempty ARROW ;
    catch[RecognitionException e] { throw e; }

// cd_expressions may be empty
cd_expressions : | expression | expression COMMA cd_expressions ;
    catch[RecognitionException e] { throw e; }
cd_expressions_or_underscores : | expression_or_underscore | expression_or_underscore COMMA cd_expressions_or_underscores ;
    catch[RecognitionException e] { throw e; }
expression_or_underscore : UNDERSCORE | expression ;
    catch[RecognitionException e] { throw e; }

lparen : LPAREN_AFTER_WS | LPAREN_NO_WS ;
less_than : LESS_THAN_AFTER_WS | LESS_THAN_NO_WS ;
