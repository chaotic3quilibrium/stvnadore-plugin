package org.stvnadore.parser;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static org.stvnadore.psi.StvnTypes.*;

%%

%{
  public _StvnLexer() {
    this((java.io.Reader)null);
  }
%}

%public
%class _StvnLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

WHITE_SPACE=\s+
COMMENT="//".*

LITERAL_STRING_BLOCK=\"\"\"[ \t]*\n([^\"]|\"[^\"]|\"\"[^\"])*\"\"\"
LITERAL_STRING_SIMPLE=\"([^\"]|\\\")*\"

ATOM_UINT=:Uint[0-9]*
ATOM_INT=:Int[0-9]*
ATOM_FLOAT=:Float[0-9]*
ATOM_STRING_FIXED=:StringFixed[0-9]*
ATOM_STRING=:String[0-9]*
ATOM_STRING_NON_EMPTY=:StringNonEmpty[0-9]*

UNION_TAG_PREFIX=#[1-9][0-9]*
TYPE_KEYWORD_BASE=:[a-zA-Z_][a-zA-Z0-9_]*
VALUE_KEYWORD_BASE=#[a-zA-Z_][a-zA-Z0-9_]*
IDENTIFIER=[a-zA-Z_][a-zA-Z0-9_]*
LITERAL_INTEGER=-?(0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|[1-9][0-9]*|0)
LITERAL_FLOAT=-?[0-9]+\.[0-9]+([eE][-+]?[0-9]+)?

%%

<YYINITIAL> {
  {WHITE_SPACE}                  { return WHITE_SPACE; }

  "["                            { return LBRACK; }
  "]"                            { return RBRACK; }
  "("                            { return LPAREN; }
  ")"                            { return RPAREN; }
  "{"                            { return LBRACE; }
  "}"                            { return RBRACE; }
  ":"                            { return COLON; }
  "/"                            { return FSLASH; }
  
  // Value track keywords (# namespace)
  "#TRUE"                        { return KW_TRUE; }
  "#FALSE"                       { return KW_FALSE; }
  "#T"                           { return KW_TRUE_SHORT; }
  "#F"                           { return KW_FALSE_SHORT; }
  "#None"                        { return KW_NONE; }
  "#N"                           { return KW_NONE_SHORT; }
  "#Some"                        { return KW_SOME; }
  "#S"                           { return KW_SOME_SHORT; }
  "#Left"                        { return KW_LEFT; }
  "#L"                           { return KW_LEFT_SHORT; }
  "#Right"                       { return KW_RIGHT; }
  "#R"                           { return KW_RIGHT_SHORT; }
  
  // Type track constructors (: namespace)
  ":Tuple"                       { return KW_TUPLE; }
  ":MapEntry"                    { return KW_MAP_ENTRY; }
  ":Enum"                        { return KW_ENUM; }
  ":Option"                      { return KW_OPTION; }
  ":Either"                      { return KW_EITHER; }
  ":Union"                       { return KW_UNION; }
  
  // Root & Control keywords (: namespace)
  ":defs"                        { return KW_DEFS; }
  ":type"                        { return KW_TYPE; }
  ":body"                        { return KW_BODY; }
  ":include"                     { return KW_INCLUDE; }
  
  // Metadata constraints (# namespace)
  "#equatable"                   { return KW_EQUATABLE; }
  "#comparable"                  { return KW_COMPARABLE; }
  "#preserveIndent"              { return KW_PRESERVE_INDENT; }
  "#minIncl"                     { return KW_MIN_INCL; }
  "#minExcl"                     { return KW_MIN_EXCL; }
  "#maxIncl"                     { return KW_MAX_INCL; }
  "#maxExcl"                     { return KW_MAX_EXCL; }
  "#regex"                       { return KW_REGEX; }
  
  // Atomic type descriptors (: namespace)
  ":Boolean"                     { return ATOM_BOOLEAN; }
  ":FloatExact"                  { return ATOM_FLOAT_EXACT; }
  ":TimeEpochS"                  { return ATOM_TIME_EPOCH_S; }
  ":TimeEpochMs"                 { return ATOM_TIME_EPOCH_MS; }
  ":TimeEpochNs"                 { return ATOM_TIME_EPOCH_NS; }
  ":DateTimeOffset"              { return ATOM_DATE_TIME_OFFSET; }
  ":DateTimeZoned"               { return ATOM_DATE_TIME_ZONED; }
  ":DateTimeAudited"             { return ATOM_DATE_TIME_AUDITED; }
  
  // Collection type descriptors (: namespace)
  ":Seq"                         { return COLL_SEQ; }
  ":SeqNonEmpty"                 { return COLL_SEQ_NON_EMPTY; }
  ":Set"                         { return COLL_SET; }
  ":SetNonEmpty"                 { return COLL_SET_NON_EMPTY; }
  ":Map"                         { return COLL_MAP; }
  ":MapNonEmpty"                 { return COLL_MAP_NON_EMPTY; }
  ":MapInv"                      { return COLL_MAP_INV; }
  ":MapInvNonEmpty"              { return COLL_MAP_INV_NON_EMPTY; }

  {COMMENT}                      { return COMMENT; }
  
  // Dynamic Fenced String with exact matching closing fence [TAG]"""
  \"\"\"->\[[-a-zA-Z0-9_ ]*\][^\n]*\n {
    String text = yytext().toString();
    int start = text.indexOf('[') + 1;
    int end = text.indexOf(']', start);
    String tag = text.substring(start, end);
    String closingFence = "[" + tag + "]\"\"\"";
    
    int matchIdx = 0;
    while (zzCurrentPos < zzEndRead) {
      char c = zzBuffer.charAt(zzCurrentPos++);
      if (c == closingFence.charAt(matchIdx)) {
        matchIdx++;
        if (matchIdx == closingFence.length()) {
          break;
        }
      } else {
        if (c == closingFence.charAt(0)) {
          matchIdx = 1;
        } else {
          matchIdx = 0;
        }
      }
    }
    zzMarkedPos = zzCurrentPos;
    return LITERAL_STRING_FENCED;
  }

  {LITERAL_STRING_BLOCK}         { return LITERAL_STRING_BLOCK; }
  {LITERAL_STRING_SIMPLE}        { return LITERAL_STRING_SIMPLE; }
  {ATOM_UINT}                    { return ATOM_UINT; }
  {ATOM_INT}                     { return ATOM_INT; }
  {ATOM_FLOAT}                   { return ATOM_FLOAT; }
  {ATOM_STRING_FIXED}            { return ATOM_STRING_FIXED; }
  {ATOM_STRING}                  { return ATOM_STRING; }
  {ATOM_STRING_NON_EMPTY}        { return ATOM_STRING_NON_EMPTY; }
  {TYPE_KEYWORD_BASE}            { return TYPE_KEYWORD_BASE; }
  {UNION_TAG_PREFIX}             { return UNION_TAG_PREFIX; }
  {VALUE_KEYWORD_BASE}           { return VALUE_KEYWORD_BASE; }
  {IDENTIFIER}                   { return IDENTIFIER; }
  {LITERAL_INTEGER}              { return LITERAL_INTEGER; }
  {LITERAL_FLOAT}                { return LITERAL_FLOAT; }
}

[^] { return BAD_CHARACTER; }
