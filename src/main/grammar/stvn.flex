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

WHITE_SPACE=[ \r\n]+
TAB_CHARACTER=\t+
COMMENT="//".*

LITERAL_STRING_BLOCK=\"\"\"[ \t\r]*\n([^\"]|\"[^\"]|\"\"[^\"])*\"\"\"
LITERAL_STRING_SIMPLE=\"([^\"\\\r\n]|\\.)*\"


UNION_TAG_PREFIX=#[1-9][0-9]*
TYPE_KEYWORD_BASE=:[a-zA-Z_][a-zA-Z0-9_]*
VALUE_KEYWORD_BASE=#[a-zA-Z_][a-zA-Z0-9_]*
IDENTIFIER=[a-zA-Z_][a-zA-Z0-9_]*
LITERAL_INTEGER=-?(0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|[1-9][0-9]*|0)
LITERAL_FLOAT=-?[0-9]+\.[0-9]+([eE][-+]?[0-9]+)?

%%

<YYINITIAL> {
  {WHITE_SPACE}                  { return WHITE_SPACE; }
  {TAB_CHARACTER}                { return BAD_CHARACTER; }

  "["                            { return LBRACK; }
  "]"                            { return RBRACK; }
  "("                            { return LPAREN; }
  ")"                            { return RPAREN; }
  "{"                            { return LBRACE; }
  "}"                            { return RBRACE; }
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
  ":package"                     { return KW_PACKAGE; }
  ":use"                         { return KW_USE; }
  
  // Metadata constraints (# namespace)
  "#strip"                       { return KW_STRIP; }
  "#equatable"                   { return KW_EQUATABLE; }
  "#comparable"                  { return KW_COMPARABLE; }
  "#preserveIndent"              { return KW_PRESERVE_INDENT; }
  "#size"                        { return KW_SIZE; }
  "#unsigned"                    { return KW_UNSIGNED; }
  "#exact"                       { return KW_EXACT; }
  "#minSize"                     { return KW_MIN_SIZE; }
  "#maxSize"                     { return KW_MAX_SIZE; }
  "#invertible"                  { return KW_INVERTIBLE; }
  "#s"                           { return KW_SCALE_S; }
  "#ms"                          { return KW_SCALE_MS; }
  "#us"                          { return KW_SCALE_US; }
  "#ns"                          { return KW_SCALE_NS; }
  "#offset"                      { return KW_OFFSET; }
  "#zoned"                       { return KW_ZONED; }
  "#audited"                     { return KW_AUDITED; }
  "#minIncl"                     { return KW_MIN_INCL; }
  "#minExcl"                     { return KW_MIN_EXCL; }
  "#maxIncl"                     { return KW_MAX_INCL; }
  "#maxExcl"                     { return KW_MAX_EXCL; }
  "#regex"                       { return KW_REGEX; }
  "#filterIncl"                  { return KW_FILTER_INCL; }
  "#filterExcl"                  { return KW_FILTER_EXCL; }
  
  // Atomic type descriptors (: namespace)
  ":Boolean"                     { return ATOM_BOOLEAN; }
  ":Int"                         { return ATOM_INT; }
  ":Float"                       { return ATOM_FLOAT; }
  ":String"                      { return ATOM_STRING; }
  ":TimeEpoch"                   { return ATOM_TIME_EPOCH; }
  ":DateTime"                    { return ATOM_DATE_TIME; }
  
  // Collection type descriptors (: namespace)
  ":Seq"                         { return COLL_SEQ; }
  ":Set"                         { return COLL_SET; }
  ":Map"                         { return COLL_MAP; }

  {COMMENT}                      { return COMMENT; }
  
  // Comprehensive orphan fence interceptor: Reject unmatched or naked closing fences
  // outside fenced string bodies to prevent block-string fallback triggers
  \[[^\]\r\n]*\]\"\"\"          { return BAD_CHARACTER; }

  // Strict Dynamic Fenced String (Rule STR-04): Matches strictly """[TAG] and rejects """->[
  \"\"\"\[[^\r\n\]]*\][ \t\r]*\n {
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
          zzMarkedPos = zzCurrentPos;
          return LITERAL_STRING_FENCED;
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
    // Fail closed on unclosed fence reaching EOF
    return BAD_CHARACTER;
  }

  {LITERAL_STRING_BLOCK}         { return LITERAL_STRING_BLOCK; }
  {LITERAL_STRING_SIMPLE}        { return LITERAL_STRING_SIMPLE; }
  {TYPE_KEYWORD_BASE}            { return TYPE_KEYWORD_BASE; }
  {UNION_TAG_PREFIX}             { return UNION_TAG_PREFIX; }
  {VALUE_KEYWORD_BASE}           { return VALUE_KEYWORD_BASE; }
  {IDENTIFIER}                   { return IDENTIFIER; }
  {LITERAL_INTEGER}              { return LITERAL_INTEGER; }
  {LITERAL_FLOAT}                { return LITERAL_FLOAT; }
}

[^] { return BAD_CHARACTER; }
