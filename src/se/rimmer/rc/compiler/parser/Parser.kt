package se.rimmer.rc.compiler.parser

import se.rimmer.rc.compiler.Diagnostics
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*

class ModuleParser(text: String, diagnostics: Diagnostics): Parser(text, diagnostics) {
    fun parseModule(target: Module) {
        withLevel {
            while(token.type == Token.Type.Semicolon) {
                while(token.type == Token.Type.Semicolon) {
                    eat()
                }

                if(token.type == Token.Type.kwImport) {
                    target.imports.add(node {parseImport()})
                } else {
                    target.decls.add(parseTopDecl())
                }
            }
        }
    }

    fun parseImport(): Import {
        expect(Token.Type.kwImport, true)
        val qualified = if(token.type == Token.Type.VarID && token.idPayload == "qualified") { eat(); true } else false
        val name = parseQualified()

        val include = maybeParens { sepBy1(Token.Type.Comma) { parseID() } }

        val exclude = if(token.type == Token.Type.VarID && token.idPayload == "hiding") {
            eat()
            parens { sepBy1(Token.Type.Comma) { parseID() } }
        } else null

        val asName = if(token.type == Token.Type.kwAs) {
            eat()
            parseConID()
        } else null

        return Import(name, qualified || asName != null, asName ?: name.name, include, exclude)
    }

    fun parseTopDecl(): TopDecl  {
        val export = if(token.type == Token.Type.kwPub) {
            eat()
            true
        } else false

        return TopDecl(node {parseDecl()}, export)
    }

    fun parseDecl(): Decl {
        return when(token.type) {
            Token.Type.kwData -> parseDataDecl()
            Token.Type.kwType -> parseTypeDecl()
            Token.Type.kwFn -> parseFunDecl()
            Token.Type.kwForeign -> parseForeignDecl()
            Token.Type.kwClass -> parseClassDecl()
            Token.Type.kwInstance -> parseInstanceDecl()
            else -> throw ParseError("Expected top-level declaration")
        }
    }

    fun parseFunDecl(): FunDecl {
        expect(Token.Type.kwFn, true)
        val name = parseVarID()
        val args = parens { sepBy(Token.Type.Comma) { parseArg(true) } }
        val ret = if(token.type == Token.Type.opArrowR) {
            eat()
            node {parseType()}
        } else {
            null
        }

        val body = if(token.type == Token.Type.opEquals) {
            eat()
            val body = node {parseExpr()}
            if(token.type == Token.Type.kwWhere) {
                eat()
                val decls = node {parseVarDecl()}
                Node(MultiExpr(listOf(decls, body)), body.location)
            } else body
        } else {
            expect(Token.Type.opColon, true)
            node {parseBlock(isFun = true)}
        }

        return FunDecl(name, args, ret, body)
    }

    fun parseDataDecl(): DataDecl {
        expect(Token.Type.kwData, true)
        val name = parseSimpleType()
        expect(Token.Type.opEquals, true)
        val cons = sepBy1(Token.Type.opBar) {
            node {
                val conName = parseConID()
                if(token.type == Token.Type.ParenL) {
                    val content = node {parens {parseType()}}
                    Constructor(conName, content)
                } else if(token.type == Token.Type.BraceL) {
                    val content = node {parseTupleType()}
                    Constructor(conName, content)
                } else {
                    Constructor(conName, null)
                }
            }
        }
        return DataDecl(name, cons)
    }

    fun parseTypeDecl(): TypeDecl {
        expect(Token.Type.kwType, true)
        val name = parseSimpleType()
        expect(Token.Type.opEquals, true)
        val type = node {parseType()}
        return TypeDecl(name, type)
    }

    fun parseForeignDecl(): ForeignDecl {
        expect(Token.Type.kwForeign, true)
        val isFun = if(token.type == Token.Type.kwFn) {eat(); true} else false

        expect(Token.Type.VarID)
        val id = token.idPayload
        eat()
        if(!isFun) expect(Token.Type.opColon, true)
        val type = node {parseType()}

        val from = if(token.type == Token.Type.VarID && token.idPayload == "from") {
            eat()
            expect(Token.Type.String)
            val from = token.idPayload
            eat()
            from
        } else null

        val internalName = if(token.type == Token.Type.kwAs) {
            eat()
            expect(Token.Type.VarID)
            val asName = token.idPayload
            eat()
            asName
        } else id

        return ForeignDecl(id, internalName, from, type)
    }

    fun parseClassDecl(): ClassDecl {
        expect(Token.Type.kwClass, true)
        val type = parseSimpleType()
        expect(Token.Type.opColon, true)
        val decls = withLevel {
            sepBy(Token.Type.Semicolon) {node {parseDecl()}}
        }
        return ClassDecl(type, decls)
    }

    fun parseInstanceDecl(): InstanceDecl {
        expect(Token.Type.kwInstance, true)
        val type = parseSimpleType()
        expect(Token.Type.opColon, true)
        val decls = withLevel {
            sepBy(Token.Type.Semicolon) {node {parseDecl()}}
        }
        return InstanceDecl(type, decls)
    }

    fun parseBlock(isFun: Boolean): Expr {
        // To make the code more readable we avoid using '=' inside expressions, and use '->' instead.
        return if(token.type == if(isFun) Token.Type.opEquals else Token.Type.opArrowR) {
            eat()
            parseExpr()
        } else {
            expect(Token.Type.opColon, true)
            withLevel {
                parseExprSeq()
            }
        }
    }

    fun parseExprSeq(): Expr {
        val rows = sepBy1(Token.Type.Semicolon) { node {parseExpr()} }
        return if(rows.size == 1) rows[0].ast else MultiExpr(rows)
    }

    fun parseExpr() = parseTypedExpr()

    fun parseTypedExpr(): Expr {
        val expr = node {parseInfixExpr()}
        if(token.type == Token.Type.kwAs) {
            eat()
            val type = node {parseType()}
            return CoerceExpr(expr, type)
        } else {
            return expr.ast
        }
    }

    fun parseInfixExpr(): Expr {
        val lhs = node {parsePrefixExpr()}
        if(token.type == Token.Type.opEquals) {
            eat()
            return AssignExpr(lhs, node {parseInfixExpr()})
        } else if(token.type == Token.Type.VarSym || token.type == Token.Type.Grave) {
            val op = parseQop()
            return InfixExpr(op, lhs, node {parseInfixExpr()})
        } else {
            return lhs.ast
        }
    }

    fun parsePrefixExpr(): Expr {
        if(token.type == Token.Type.VarSym) {
            val op = token.idPayload
            eat()
            val expr = node {parseLeftExpr()}
            return PrefixExpr(op, expr)
        } else {
            return parseLeftExpr()
        }
    }

    fun parseLeftExpr(): Expr {
        if(token.type == Token.Type.kwLet) {
            eat()
            return parseVarDecl()
        } else if(token.type == Token.Type.kwMatch) {
            eat()
            val expr = node {parseInfixExpr()}
            expect(Token.Type.opColon, true)
            val alts = withLevel {
                sepBy1(Token.Type.Semicolon) { parseAlt() }
            }
            return CaseExpr(expr, alts)
        } else if(token.type == Token.Type.kwIf) {
            eat()
            if(token.type == Token.Type.opBar) {
                val cases = withLevel {
                    sepBy(Token.Type.Semicolon) {
                        expect(Token.Type.opBar, true)
                        val cond = node {
                            if(token.type == Token.Type.kw_ || token.type == Token.Type.kwElse) {
                                eat()
                                LitExpr(BoolLiteral(true))
                            } else {
                                parseInfixExpr()
                            }
                        }
                        IfCase(cond, node {parseBlock(isFun = false)})
                    }
                }
                return MultiIfExpr(cases)
            } else {
                val cond = node {parseInfixExpr()}

                // Allow statement ends within an if-expression to allow then/else with the same indentation as if.
                if(token.type == Token.Type.Semicolon) eat()
                expect(Token.Type.kwThen, true)
                val then = if(token.type == Token.Type.opColon) {
                    node {parseBlock(isFun = false)}
                } else {
                    node {parseExpr()}
                }

                if(token.type == Token.Type.kwElse) {
                    eat()
                    val otherwise = if(token.type == Token.Type.opColon) {
                        node {parseBlock(isFun = false)}
                    } else {
                        node {parseExpr()}
                    }
                    return IfExpr(cond, then, otherwise)
                } else {
                    return IfExpr(cond, then, null)
                }
            }
        } else if(token.type == Token.Type.kwWhile) {
            eat()
            val cond = node {parseInfixExpr()}
            expect(Token.Type.opColon, true)
            val body = node {parseExprSeq()}
            return WhileExpr(cond, body)
        } else if(token.type == Token.Type.kwReturn) {
            eat()
            val body = node {parseExpr()}
            return ReturnExpr(body)
        } else {
            return parseAppExpr()
        }
    }

    fun parseAppExpr(): Expr {
        var b = node {parseBaseExpr()}
        while(true) {
            if(token.type == Token.Type.ParenL) {
                val args = parens { sepBy(Token.Type.Comma) {parseTupArg()} }
                b = Node(AppExpr(b, args), b.location)
            } else if(token.type == Token.Type.opDot) {
                eat()
                val app = node {parseSelExpr()}
                val location = SourceLocation(
                    b.location.startLine, app.location.endLine, b.location.startColumn,
                    app.location.endColumn, b.location.startOffset, app.location.endOffset
                )
                b = Node(FieldExpr(b, app), location)
            } else {
                break
            }
        }
        return b.ast
    }

    fun parseBaseExpr(): Expr {
        if(token.type == Token.Type.BraceL) {
            return parseTupExpr()
        } else if(token.type == Token.Type.ConID) {
            val id = node {parseQualified()}
            if(id.ast.isVar) {
                return VarExpr(id.ast)
            } else {
                val type = Node(ConType(id.ast), id.location)
                if(token.type == Token.Type.ParenL) {
                    val args = parens { sepBy1(Token.Type.Comma) { node {parseExpr()} } }
                    return ConstructExpr(type, args)
                } else if(token.type == Token.Type.BraceL) {
                    return ConstructExpr(type, listOf(node {parseTupExpr()}))
                } else {
                    return ConstructExpr(type, emptyList())
                }
            }
        } else if(token.type == Token.Type.ParenL) {
            eat()

            if(token.type == Token.Type.ParenR) {
                eat()
                return FunExpr(emptyList(), node {parseExpr()})
            }

            val e = node {parseExpr()}

            // Find out if this is a parenthesized or function expression.
            // a comma or `) =>` indicates a function, anything else is either an error or an expression.
            if(token.type == Token.Type.ParenR) {
                eat()
                if(token.type != Token.Type.opArrowD) {
                    return NestedExpr(e)
                }
            }

            val firstArg = if(e.ast is VarExpr && e.ast.name.qualifier.isEmpty()) {
                Arg(e.ast.name.name, null, null)
            } else if(e.ast is CoerceExpr && e.ast.target.ast is VarExpr && e.ast.target.ast.name.qualifier.isEmpty()) {
                Arg(e.ast.target.ast.name.name, e.ast.type, null)
            } else {
                throw ParseError("Expected ')' or a function argument")
            }

            val args: List<Arg>
            if(token.type == Token.Type.Comma) {
                eat()
                args = listOf(firstArg) + sepBy(Token.Type.Comma) { parseArg(false) }
                expect(Token.Type.ParenR, true)
                expect(Token.Type.opArrowD, true)
            } else if(token.type == Token.Type.opArrowD) {
                eat()
                args = listOf(firstArg)
            } else {
                throw ParseError("Expected ')' or a function expression")
            }

            return FunExpr(args, node {parseExpr()})
        } else {
            return parseSelExpr()
        }
    }

    fun parseSelExpr(): Expr {
        if(token.kind == Token.Kind.Literal) {
            if(token.type == Token.Type.String) {
                return parseStringExpr()
            } else {
                return LitExpr(parseLiteral())
            }
        } else if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            return VarExpr(Qualified(id, emptyList(), true))
        } else if(token.type == Token.Type.ParenL) {
            return NestedExpr(parens { node {parseExpr()} })
        } else {
            throw ParseError("Expected an expression")
        }
    }

    fun parseVarDecl(): Expr {
        val list = withLevel {
            sepBy1(Token.Type.Semicolon) { node {parseDeclExpr()} }
        }
        return if(list.size == 1) list[0].ast else MultiExpr(list)
    }

    fun parseDeclExpr(): Expr {
        val mutable = if(token.type == Token.Type.kwMut) {
            eat()
            true
        } else false

        expect(Token.Type.VarID, false)
        val id = token.idPayload
        eat()

        if(token.type == Token.Type.opEquals) {
            eat()
            return DeclExpr(id, node {parseExpr()}, mutable)
        } else {
            return DeclExpr(id, null, mutable)
        }
    }

    fun parseStringExpr(): Expr {
        expect(Token.Type.String)
        val string = token.idPayload
        eat()

        // Check if the string contains formatting.
        if(token.type == Token.Type.StartOfFormat) {
            val chunks = ArrayList<FormatChunk>()
            chunks.add(FormatChunk(string, null))

            // Parse one or more formatting expressions.
            // The first one consists of just the first string chunk.
            while(token.type == Token.Type.StartOfFormat) {
                eat()
                val expr = parseExpr()
                expect(Token.Type.EndOfFormat, true)
                expect(Token.Type.String)
                chunks.add(FormatChunk(token.idPayload, expr))
                eat()
            }
            return FormatExpr(chunks)
        } else {
            return LitExpr(StringLiteral(string))
        }
    }

    fun parseTupExpr(): Expr {
        val args = braces { sepBy1(Token.Type.Comma) { parseTupArg() } }
        return TupExpr(args)
    }

    fun parseTupArg(): TupArg {
        if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            if(token.type == Token.Type.opEquals) {
                eat()
                return TupArg(id, node {parseExpr()})
            } else {
                return TupArg(null, Node(VarExpr(Qualified(id, emptyList(), true)), token.location()))
            }
        } else {
            return TupArg(null, node {parseExpr()})
        }
    }

    fun parseAlt(): Alt {
        val pat = node {parsePat()}
        val alias = if(token.type == Token.Type.opAt) {
            eat()
            expect(Token.Type.VarID)
            val id = token.idPayload
            eat()
            id
        } else null

        return Alt(pat, alias, node {parseBlock(isFun = false)})
    }

    fun parsePat(): Pat {
        if(token.singleMinus) {
            eat()
            if(token.type == Token.Type.Integer || token.type == Token.Type.Float) {
                return LitPat(parseLiteral().negate())
            } else {
                throw ParseError("Expected integer or float literal")
            }
        } else if(token.type == Token.Type.ConID) {
            val name = parseQualified()
            val list = if(token.type == Token.Type.ParenL) {
                parens { sepBy1(Token.Type.Comma) { node {parseLPat()} } }
            } else if(token.type == Token.Type.BraceL) {
                listOf(node {parseLPat()})
            } else {
                emptyList()
            }

            return ConPat(name, list)
        } else {
            return parseLPat()
        }
    }

    fun parseLPat(): Pat {
        if(token.kind == Token.Kind.Literal) {
            return LitPat(parseLiteral())
        } else if(token.type == Token.Type.kw_ || token.type == Token.Type.kwElse) {
            eat()
            return AnyPat(Unit)
        } else if(token.type == Token.Type.VarID) {
            val id = token.idPayload
            eat()
            return VarPat(id)
        } else if(token.type == Token.Type.ParenL) {
            return parens { parsePat() }
        } else if(token.type == Token.Type.BraceL) {
            val list = braces {
                sepBy1(Token.Type.Comma) {
                    if(token.type == Token.Type.VarID) {
                        val id = node {token.idPayload.apply { eat() }}
                        if(token.type == Token.Type.opEquals) {
                            eat()
                            PatternField(id.ast, node {parsePat()})
                        } else {
                            PatternField(null, Node(VarPat(id.ast), id.location))
                        }
                    } else {
                        PatternField(null, node {parsePat()})
                    }
                }
            }
            return TupPat(list)
        } else {
            throw ParseError("Expected pattern")
        }
    }

    fun parseType(): Type {
        val args = maybeParens { sepBy(Token.Type.Comma) { parseTypeArg() } }
        if(args == null) {
            return parseAType()
        } else if(token.type == Token.Type.opArrowR) {
            eat()
            return FunType(args, node {parseType()})
        } else {
            val arg = if(args.size == 1) args[0] else null
            if(arg != null && arg.name == null) {
                return arg.type.ast
            } else {
                throw ParseError("expected a type")
            }
        }
    }

    fun parseAType(): Type {
        if(token.type == Token.Type.ConID) {
            val base = node {ConType(parseQualified())}

            // For cases where it is easily visible what's going on, we allow omitting parentheses.
            // This conveniently also prevents us from having to look too far ahead.
            val app = if(token.type == Token.Type.ParenL) {
                parens { sepBy1(Token.Type.Comma) { node {parseType()} } }
            } else if(token.type == Token.Type.BraceL) {
                listOf(node {parseTupleType()})
            } else if(token.type == Token.Type.ConID) {
                val q = node {parseQualified()}
                listOf(Node(ConType(q.ast), q.location))
            } else null

            if(app == null) {
                return base.ast
            } else {
                return AppType(base, app)
            }
        } else if(token.type == Token.Type.VarID) {
            val name = token.idPayload
            eat()
            return GenType(name)
        } else if(token.type == Token.Type.ParenL) {
            return parens { parseType() }
        } else if(token.type == Token.Type.BraceL) {
            return parseTupleType()
        } else if(token.type == Token.Type.BracketL) {
            eat()
            val from = node {parseType()}
            if(token.type == Token.Type.opArrowD) {
                eat()
                val to = node {parseType()}
                return MapType(from, to)
            } else {
                return ArrayType(from)
            }
        } else {
            throw ParseError("Expected a type")
        }
    }

    fun parseTupleType(): TupType {
        val fields = braces {
            sepBy1(Token.Type.Comma) {
                val mutable = if(token.type == Token.Type.kwMut) {
                    eat()
                    true
                } else false

                if(token.type == Token.Type.VarID) {
                    val name = token.idPayload
                    eat()
                    if(token.type == Token.Type.opColon) {
                        eat()
                        TupField(node {parseType()}, name, mutable)
                    } else {
                        TupField(node {GenType(name)}, null, mutable)
                    }
                } else {
                    TupField(node {parseType()}, null, mutable)
                }
            }
        }
        return TupType(fields)
    }

    fun parseQualified(): Qualified {
        val name = parseConID()
        val qualifier = ArrayList<String>()
        var isVar = false

        qualifier.add(name)
        while(token.type == Token.Type.opDot) {
            eat()

            if(token.type == Token.Type.VarID || token.type == Token.Type.ConID) {
                qualifier.add(token.idPayload)
                isVar = token.type == Token.Type.VarID
                eat()
                if(isVar) break
            } else {
                throw ParseError("expected variable or constructor name")
            }
        }

        return Qualified(qualifier.last(), qualifier.dropLast(1), isVar)
    }

    fun parseArg(requireType: Boolean): Arg {
        val name = parseVarID()
        val type = if(requireType || token.type == Token.Type.opColon) {
            expect(Token.Type.opColon, true)
            node {parseType()}
        } else {
            null
        }

        val default = if(token.type == Token.Type.opEquals) {
            node {parseExpr()}
        } else {
            null
        }

        return Arg(name, type, default)
    }

    fun parseTypeArg() = parseArgDecl()

    fun parseArgDecl(): ArgDecl {
        if(token.type == Token.Type.VarID) {
            val name = node {parseVarID()}
            if(token.type == Token.Type.opColon) {
                expect(Token.Type.opColon, true)
                return ArgDecl(name.ast, node {parseType()})
            } else {
                return ArgDecl(null, Node(GenType(name.ast), name.location))
            }
        } else {
            return ArgDecl(null, node {parseType()})
        }
    }

    fun parseSimpleType(): SimpleType {
        val name = parseConID()
        val kind = maybeParens { sepBy1(Token.Type.Comma) { parseID() } } ?: emptyList()
        return SimpleType(name, kind)
    }

    fun parseConID(): String {
        expect(Token.Type.ConID)
        val id = token.idPayload
        eat()
        return id
    }

    fun parseVarID(): String {
        // A VarID can be a string literal as well, in order to be able to use keyword variable names.
        if(token.type != Token.Type.VarID) {
            throw ParseError("expected variable name")
        }

        val id = token.idPayload
        eat()
        return id
    }

    fun parseID(): String {
        // An ID can be a string literal as well, in order to be able to use keyword variable names.
        if(token.type != Token.Type.VarID && token.type != Token.Type.ConID) {
            throw ParseError("expected variable name")
        }

        val id = token.idPayload
        eat()
        return id
    }

    fun parseQop(): String {
        if(token.type == Token.Type.VarSym) {
            val id = token.idPayload
            eat()
            return id
        } else if(token.type == Token.Type.Grave) {
            eat()
            expect(Token.Type.VarID)
            val id = token.idPayload
            eat()
            expect(Token.Type.Grave, true)
            return id
        } else {
            throw ParseError("Expected operator")
        }
    }

    fun parseLiteral(): Literal {
        val literal = when(token.type) {
            Token.Type.Integer -> IntLiteral(BigInteger.valueOf(token.intPayload))
            Token.Type.Float -> RationalLiteral(BigDecimal.valueOf(token.floatPayload))
            Token.Type.Char -> CharLiteral(token.charPayload)
            Token.Type.String -> StringLiteral(token.idPayload)
            else -> throw ParseError("Invalid literal type ${token.type}")
        }
        eat()
        return literal
    }
}