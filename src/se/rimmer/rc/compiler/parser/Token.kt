package se.rimmer.rc.compiler.parser

class Token {
    enum class Type {
        EndOfFile,
        Comment,
        Whitespace,
        EndOfBlock,
        StartOfFormat,
        EndOfFormat,

        /* Special symbols */
        ParenL,
        ParenR,
        Comma,
        Semicolon,
        BracketL,
        BracketR,
        Grave,
        BraceL,
        BraceR,

        /* Literals */
        Integer,
        Float,
        String,
        Char,

        /* Identifiers */
        VarID,
        ConID,
        VarSym,
        ConSym,

        /* Keywords */
        kwAs,
        kwClass,
        kwData,
        kwDeriving,
        kwDo,
        kwElse,
        kwFor,
        kwForeign,
        kwIf,
        kwImport,
        kwIn,
        kwInfix,
        kwInfixL,
        kwInfixR,
        kwPrefix,
        kwInstance,
        kwLet,
        kwModule,
        kwMatch,
        kwNewType,
        kwOf,
        kwThen,
        kwType,
        kwVar,
        kwWhere,
        kwWhile,
        kw_,
        kwFn,
        kwReturn,

        /* Reserved operators */
        opDot,
        opDotDot,
        opColon,
        opColonColon,
        opEquals,
        opBackSlash, // also λ
        opBar,
        opArrowL, // <- and ←
        opArrowR, // -> and →
        opAt,
        opDollar,
        opTilde,
        opArrowD,
    }

    enum class Kind {
        Literal,
        Special,
        Identifier,
        Keyword,
        Inactive
    }

    // The token position including any whitespace preceding it.
    var whitespaceLine = 0
    var whitespaceColumn = 0
    var whitespaceOffset = 0

    // The starting position of the actual token data.
    var startLine = 0
    var startColumn = 0
    var startOffset = 0

    // The end position of the actual token data.
    var endLine = 0
    var endColumn = 0
    var endOffset = 0

    var type = Type.EndOfFile
    var kind = Kind.Special

    var intPayload = 0L
    var floatPayload = 0.0
    var charPayload = ' '
    var idPayload = ""

    // Special case for VarSym, used to find unary minus more easily.
    // Undefined value if the type is not VarSym.
    var singleMinus = false

    fun copy(): Token {
        val t = Token()
        t.startLine = startLine
        t.endLine = endLine
        t.startColumn = startColumn
        t.endColumn = endColumn
        t.startOffset = startOffset
        t.endOffset = endOffset
        t.type = type
        t.kind = kind
        t.intPayload = intPayload
        t.floatPayload = floatPayload
        t.charPayload = charPayload
        t.idPayload = idPayload
        t.singleMinus = singleMinus
        return t
    }
}