package se.rimmer.rc.compiler.resolve

import se.rimmer.rc.compiler.parser.FunDecl
import se.rimmer.rc.compiler.parser.MultiExpr

fun resolveFun(f: Function, ast: FunDecl) {
    ast.args.forEach {
        val type = resolveType(f.module, it.type ?: throw NotImplementedError(), null)
        f.defineArg(it.name, type)
    }

    val expectedReturn = ast.ret?.let { resolveType(f.module, it, null) }
    val resultUsed = when(ast.body) {
        is MultiExpr -> false
        else -> true
    }

    FunctionBuilder(f).apply {
        val body = resolveExpr(ast.body, null, resultUsed, expectedReturn)
        if(resultUsed && body !is RetInst) {
            body.block.ret(body)
        } else if(!body.block.complete) {
            body.block.ret(Value(body.block, null, PrimTypes.unitType))
        }

        if(resultUsed) {
            if(expectedReturn != null && !typesCompatible(expectedReturn, body.type)) {
                throw ResolveError("declared type and actual type of function ${f.name} don't match")
            }
            f.returnType = body.type
        } else if(f.returns.isEmpty()) {
            if(expectedReturn != null && !typesCompatible(expectedReturn, PrimTypes.unitType)) {
                throw ResolveError("function ${f.name} is declared to return a value, but doesn't")
            }
            f.returnType = PrimTypes.unitType
        } else {
            var previous: Type? = null
            for(p in f.returns) {
                if(previous != null) {
                    if(!typesCompatible(previous, p.type)) {
                        throw ResolveError("types of return statements in function ${f.name} don't match")
                    }
                }
                previous = p.type
            }

            if(expectedReturn != null && !typesCompatible(expectedReturn, previous!!)) {
                throw ResolveError("declared type and actual type of function ${f.name} don't match")
            }
            f.returnType = previous!!
        }
    }
}