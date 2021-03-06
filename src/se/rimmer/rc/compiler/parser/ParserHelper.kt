package se.rimmer.rc.compiler.parser

import java.util.*

open class Parser(val module: Qualified, text: String, listener: LexerListener) {
    inner class ParseError(text: String): Exception("line ${token.startLine}, column ${token.startColumn}: $text")

    inline fun <T: Node> node(f: () -> T): T {
        val startLine = token.startLine
        val startColumn = token.startColumn
        val startOffset = token.startOffset
        val result = f()
        result.sourceModule = module
        result.sourceStart.line = startLine
        result.sourceStart.column = startColumn
        result.sourceStart.offset = startOffset
        result.sourceEnd.line = token.whitespaceLine
        result.sourceEnd.column = token.whitespaceColumn
        result.sourceEnd.offset = token.whitespaceOffset
        return result
    }

    fun <T> withLevel(f: () -> T): T {
        val level = IndentLevel(token, lexer)
        val v = f()
        level.end()
        if(token.type == Token.Type.EndOfBlock) {
            eat()
        }
        return v
    }

    /** Parses an item between two other items. */
    fun <T> between(start: () -> Unit, end: () -> Unit, f: () -> T): T {
        start()
        val v = f()
        end()
        return v
    }

    fun <T> between(start: Token.Type, end: Token.Type, f: () -> T) = between({expect(start, true)}, {expect(end, true)}, f)
    fun <T> maybeBetween(start: Token.Type, end: Token.Type, f: () -> T) = if(token.type == start) between(start, end, f) else null

    fun <T> parens(f: () -> T) = between(Token.Type.ParenL, Token.Type.ParenR, f)
    fun <T> maybeParens(f: () -> T) = maybeBetween(Token.Type.ParenL, Token.Type.ParenR, f)
    fun <T> braces(f: () -> T) = between(Token.Type.BraceL, Token.Type.BraceR, f)
    fun <T> maybeBraces(f: () -> T) = maybeBetween(Token.Type.BraceL, Token.Type.BraceR, f)
    fun <T> brackets(f: () -> T) = between(Token.Type.BracketL, Token.Type.BracketR, f)
    fun <T> maybeBrackets(f: () -> T) = maybeBetween(Token.Type.BracketL, Token.Type.BracketR, f)

    /** Parses one or more items from the provided parser. */
    fun <T> many1(f: () -> T): List<T> {
        val first = f()
        val list = ArrayList<T>()
        list.add(first)
        while(true) {
            val v = tryParse(f) ?: break
            list.add(v)
        }
        return list
    }

    /** Parses zero or more items from the provided parser. */
    fun <T> many(f: () -> T): List<T> {
        val list = ArrayList<T>()
        while(true) {
            val v = tryParse(f) ?: break
            list.add(v)
        }
        return list
    }

    /** Parses one or more items separated by the sep predicate. */
    fun <T> sepBy1(sep: () -> Boolean, f: () -> T): List<T> {
        val first = f()
        val list = ArrayList<T>()
        list.add(first)
        while(sep()) {
            list.add(f())
        }
        return list
    }

    /** Parses one or more items separated by the sep token. */
    fun <T> sepBy1(sep: Token.Type, f: () -> T) = sepBy1({
        if(token.type == sep) {
            eat()
            true
        } else false
    }, f)

    /** Parses zero or more items separated by the sep predicate. */
    fun <T> sepBy(sep: () -> Boolean, f: () -> T): List<T> {
        val first = tryParse(f) ?: return emptyList()
        val list = ArrayList<T>()
        list.add(first)
        while(sep()) {
            list.add(f())
        }
        return list
    }

    /** Parses zero or more items separated by the sep token. */
    fun <T> sepBy(sep: Token.Type, f: () -> T) = sepBy({
        if(token.type == sep) {
            eat()
            true
        } else false
    }, f)

    fun <T> tryParse(f: () -> T): T? {
        lastError = null
        val save = SaveLexer(lexer)
        val token = token.copy()
        val v = try {
            f()
        } catch(e: ParseError) {
            save.restore()
            this.token = token
            lexer.token = token
            lastError = e
            return null
        }
        if(v == null) {
            save.restore()
            this.token = token
            lexer.token = token
        }
        return v
    }

    fun require(type: Token.Type) = {
        if(token.type == type) {
            eat(); Unit
        } else throw ParseError("expected $type")
    }

    fun expectConID(id: String, discard: Boolean = false) {
        if(token.type != Token.Type.ConID || token.idPayload != id) {
            throw ParseError("expected conid $id")
        }

        if(discard) eat()
    }

    fun expectVarID(id: String, discard: Boolean = false) {
        if(token.type != Token.Type.VarID || token.idPayload != id) {
            throw ParseError("expected varid $id")
        }

        if(discard) eat()
    }

    fun expect(type: Token.Type, discard: Boolean = false, error: ParseError? = null) {
        if(token.type != type) {
            throw error ?: ParseError("expected $type")
        }

        if(discard) eat()
    }

    fun expect(kind: Token.Kind, discard: Boolean = false) {
        if(token.kind != kind) {
            throw ParseError("expected $kind")
        }

        if(discard) eat()
    }

    // Makes sure that a whole expression was parsed.
    // If not, it is likely that the parsing failed internally and the error should be propagated.
    fun assertEnded() {
        if(token.type !== Token.Type.Semicolon && token.type !== Token.Type.EndOfBlock) {
            throw lastError ?: ParseError("Expected expression end")
        }
    }

    fun eat() = lexer.next()

    var token = Token()
    val lexer = Lexer(module, text, token, ParseMode.Active, listener)
    var lastError: ParseError? = null

    init {
        lexer.next()
    }
}

class IndentLevel(start: Token, val lexer: Lexer) {
    val previous = lexer.indentation

    init {
        lexer.indentation = start.startColumn
        lexer.blockCount++
    }

    fun end() {
        lexer.indentation = previous
        assert(lexer.blockCount > 0)
        lexer.blockCount--
    }
}